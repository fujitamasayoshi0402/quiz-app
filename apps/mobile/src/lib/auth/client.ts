import * as AuthSession from "expo-auth-session";
import * as SecureStore from "expo-secure-store";
import * as WebBrowser from "expo-web-browser";
import { currentEnvironment } from "@/lib/current-environment";
import type { Environment, EnvironmentName } from "@/lib/environments";
import { needsRefresh, parseSession, sessionFrom, type Session } from "./session";

/**
 * Cognito とのやり取りと、ログインの状態の置き場所（ADR-0032）。画面からも API の fetch からも使うため、React の外に置く。
 *
 * トークンは端末の安全な置き場所（iOS のキーチェーン、Android の Keystore）にだけ置く。ログにも画面にも出さない。
 *
 * **ログインの状態は、つなぐ先（dev / prod）ごとに持つ**（DEV-208）。User Pool が違うので、トークンを持ち越さない。
 * 切り替えて戻ってきたときは、前のログインの状態のまま使える。
 * どの関数も、始めた時点のつなぐ先で最後まで動く。途中で切り替わっても、別のつなぐ先の状態を書き換えない
 */

function storeKey(environment: Environment): string {
  return `session.${environment.name}`;
}

/** Cognito のアプリ用のクライアントに登録した戻り先。Expo Go では `exp://127.0.0.1:8081/--/auth/callback` になる */
export const redirectUri = AuthSession.makeRedirectUri({ scheme: "quizapp", path: "auth/callback" });
const logoutRedirectUri = AuthSession.makeRedirectUri({ scheme: "quizapp", path: "auth/logout" });

/** web と同じ。aws.cognito.signin.user.admin は、バックエンドが確認済みのメールアドレスを取るのに要る */
export const scopes = ["openid", "email", "aws.cognito.signin.user.admin"];

const discoveries = new Map<EnvironmentName, Promise<AuthSession.DiscoveryDocument>>();

/** Cognito の OIDC の設定。発行者から読み、失敗したら次に呼ばれたときに読み直す */
export function loadDiscovery(environment: Environment): Promise<AuthSession.DiscoveryDocument> {
  let discovery = discoveries.get(environment.name);
  if (!discovery) {
    discovery = AuthSession.fetchDiscoveryAsync(environment.authIssuer).catch((error: unknown) => {
      discoveries.delete(environment.name);
      throw error;
    });
    discoveries.set(environment.name, discovery);
  }
  return discovery;
}

// 入っていなければ、まだ端末から読んでいない
const sessions = new Map<EnvironmentName, Session | null>();
const listeners = new Set<(session: Session | null) => void>();

export function onSessionChange(listener: (session: Session | null) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

async function setSession(environment: Environment, session: Session | null) {
  sessions.set(environment.name, session);
  if (session) {
    await SecureStore.setItemAsync(storeKey(environment), JSON.stringify(session));
  } else {
    await SecureStore.deleteItemAsync(storeKey(environment));
  }
  // 画面が見ているのは、いまのつなぐ先のログインの状態だけ
  if (environment === currentEnvironment()) listeners.forEach((listener) => listener(session));
}

export async function loadSession(environment: Environment = currentEnvironment()): Promise<Session | null> {
  if (!sessions.has(environment.name)) {
    sessions.set(environment.name, parseSession(await SecureStore.getItemAsync(storeKey(environment))));
  }
  return sessions.get(environment.name) ?? null;
}

/** ログインの画面から戻ったコードを、トークンに替える。PKCE の verifier がなければ、Cognito が受け付けない */
export async function completeSignIn(environment: Environment, code: string, codeVerifier: string): Promise<void> {
  const response = await AuthSession.exchangeCodeAsync(
    { clientId: environment.authClientId, code, redirectUri, extraParams: { code_verifier: codeVerifier } },
    await loadDiscovery(environment),
  );
  await setSession(environment, sessionFrom(response));
}

const refreshing = new Map<EnvironmentName, Promise<Session | null>>();

/**
 * API に付けるアクセストークン。期限が近ければ、先に更新する。
 *
 * 同時に呼ばれても、更新は 1 回だけにする。画面を開くと、いくつもの API が一度に呼ばれる
 */
export async function accessToken(environment: Environment): Promise<string | null> {
  const session = await loadSession(environment);
  if (!session) return null;
  if (!needsRefresh(session, AuthSession.getCurrentTimeInSeconds())) return session.accessToken;
  let pending = refreshing.get(environment.name);
  if (!pending) {
    pending = refresh(environment, session).finally(() => refreshing.delete(environment.name));
    refreshing.set(environment.name, pending);
  }
  return (await pending)?.accessToken ?? null;
}

async function refresh(environment: Environment, session: Session): Promise<Session | null> {
  try {
    const response = await AuthSession.refreshAsync(
      { clientId: environment.authClientId, refreshToken: session.refreshToken, scopes },
      await loadDiscovery(environment),
    );
    const next = sessionFrom(response, session);
    await setSession(environment, next);
    return next;
  } catch (error) {
    // Cognito が断った（期限切れ、失効）ときだけ、ログインし直してもらう。通信の失敗では状態を捨てない
    if (error instanceof AuthSession.TokenError) {
      await setSession(environment, null);
      return null;
    }
    throw error;
  }
}

/** バックエンドがトークンを受け付けなかった（401）。端末の状態だけを捨て、ログインの画面に戻す */
export async function forgetSession(environment: Environment): Promise<void> {
  await setSession(environment, null);
}

/**
 * ログアウト。リフレッシュトークンを失効させ、Cognito のログインのセッションも終える。
 *
 * Cognito のセッションはブラウザの Cookie に残り、終えないと、次のログインが画面を見せずに同じ利用者で通る
 */
export async function signOut(): Promise<void> {
  const environment = currentEnvironment();
  const session = await loadSession(environment);
  await setSession(environment, null);
  const document = await loadDiscovery(environment);
  if (session) {
    await AuthSession.revokeAsync(
      {
        clientId: environment.authClientId,
        token: session.refreshToken,
        tokenTypeHint: AuthSession.TokenTypeHint.RefreshToken,
      },
      document,
    ).catch(() => {
      // 失効させられなくても、端末からは消えている。リフレッシュトークンは期限（30 日）で切れる
    });
  }
  if (document.endSessionEndpoint) {
    const url = `${document.endSessionEndpoint}?${new URLSearchParams({ client_id: environment.authClientId, logout_uri: logoutRedirectUri })}`;
    await WebBrowser.openAuthSessionAsync(url, logoutRedirectUri);
  }
}
