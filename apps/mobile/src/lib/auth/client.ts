import * as AuthSession from "expo-auth-session";
import * as SecureStore from "expo-secure-store";
import * as WebBrowser from "expo-web-browser";
import { config } from "@/lib/config";
import { needsRefresh, parseSession, sessionFrom, type Session } from "./session";

/**
 * Cognito とのやり取りと、ログインの状態の置き場所（ADR-0032）。画面からも API の fetch からも使うため、React の外に置く。
 *
 * トークンは端末の安全な置き場所（iOS のキーチェーン、Android の Keystore）にだけ置く。ログにも画面にも出さない
 */

const STORE_KEY = "session";

/** Cognito のアプリ用のクライアントに登録した戻り先。Expo Go では `exp://127.0.0.1:8081/--/auth/callback` になる */
export const redirectUri = AuthSession.makeRedirectUri({ scheme: "quizapp", path: "auth/callback" });
const logoutRedirectUri = AuthSession.makeRedirectUri({ scheme: "quizapp", path: "auth/logout" });

/** web と同じ。aws.cognito.signin.user.admin は、バックエンドが確認済みのメールアドレスを取るのに要る */
export const scopes = ["openid", "email", "aws.cognito.signin.user.admin"];

let discovery: Promise<AuthSession.DiscoveryDocument> | null = null;

/** Cognito の OIDC の設定。発行者から読み、失敗したら次に呼ばれたときに読み直す */
export function loadDiscovery(): Promise<AuthSession.DiscoveryDocument> {
  discovery ??= AuthSession.fetchDiscoveryAsync(config.authIssuer).catch((error: unknown) => {
    discovery = null;
    throw error;
  });
  return discovery;
}

// undefined は、まだ端末から読んでいない
let current: Session | null | undefined;
const listeners = new Set<(session: Session | null) => void>();

export function onSessionChange(listener: (session: Session | null) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

async function setSession(session: Session | null) {
  current = session;
  if (session) {
    await SecureStore.setItemAsync(STORE_KEY, JSON.stringify(session));
  } else {
    await SecureStore.deleteItemAsync(STORE_KEY);
  }
  listeners.forEach((listener) => listener(session));
}

export async function loadSession(): Promise<Session | null> {
  if (current === undefined) {
    current = parseSession(await SecureStore.getItemAsync(STORE_KEY));
  }
  return current;
}

/** ログインの画面から戻ったコードを、トークンに替える。PKCE の verifier がなければ、Cognito が受け付けない */
export async function completeSignIn(code: string, codeVerifier: string): Promise<void> {
  const response = await AuthSession.exchangeCodeAsync(
    { clientId: config.authClientId, code, redirectUri, extraParams: { code_verifier: codeVerifier } },
    await loadDiscovery(),
  );
  await setSession(sessionFrom(response));
}

let refreshing: Promise<Session | null> | null = null;

/**
 * API に付けるアクセストークン。期限が近ければ、先に更新する。
 *
 * 同時に呼ばれても、更新は 1 回だけにする。画面を開くと、いくつもの API が一度に呼ばれる
 */
export async function accessToken(): Promise<string | null> {
  const session = await loadSession();
  if (!session) return null;
  if (!needsRefresh(session, AuthSession.getCurrentTimeInSeconds())) return session.accessToken;
  refreshing ??= refresh(session).finally(() => {
    refreshing = null;
  });
  return (await refreshing)?.accessToken ?? null;
}

async function refresh(session: Session): Promise<Session | null> {
  try {
    const response = await AuthSession.refreshAsync(
      { clientId: config.authClientId, refreshToken: session.refreshToken, scopes },
      await loadDiscovery(),
    );
    const next = sessionFrom(response, session);
    await setSession(next);
    return next;
  } catch (error) {
    // Cognito が断った（期限切れ、失効）ときだけ、ログインし直してもらう。通信の失敗では状態を捨てない
    if (error instanceof AuthSession.TokenError) {
      await setSession(null);
      return null;
    }
    throw error;
  }
}

/** バックエンドがトークンを受け付けなかった（401）。端末の状態だけを捨て、ログインの画面に戻す */
export async function forgetSession(): Promise<void> {
  await setSession(null);
}

/**
 * ログアウト。リフレッシュトークンを失効させ、Cognito のログインのセッションも終える。
 *
 * Cognito のセッションはブラウザの Cookie に残り、終えないと、次のログインが画面を見せずに同じ利用者で通る
 */
export async function signOut(): Promise<void> {
  const session = await loadSession();
  await setSession(null);
  const document = await loadDiscovery();
  if (session) {
    await AuthSession.revokeAsync(
      {
        clientId: config.authClientId,
        token: session.refreshToken,
        tokenTypeHint: AuthSession.TokenTypeHint.RefreshToken,
      },
      document,
    ).catch(() => {
      // 失効させられなくても、端末からは消えている。リフレッシュトークンは期限（30 日）で切れる
    });
  }
  if (document.endSessionEndpoint) {
    const url = `${document.endSessionEndpoint}?${new URLSearchParams({ client_id: config.authClientId, logout_uri: logoutRedirectUri })}`;
    await WebBrowser.openAuthSessionAsync(url, logoutRedirectUri);
  }
}
