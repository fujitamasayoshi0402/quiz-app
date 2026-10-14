import { createHmac } from "node:crypto";
import type { TokenSet } from "@/lib/auth/oidc";

/**
 * 共有のデモのアカウントで入る（ADR-0029）。**サーバーでだけ使う。**
 *
 * パスワードは web のサーバーが環境変数で持ち、Cognito の `InitiateAuth`（`USER_AUTH`）でトークンを取る。
 * ここは Cognito に固有の呼び出しで、標準の OIDC にはない。
 *
 * **取ったトークンはメモリに持ち、押した人みんなに配る。** 連打されても Cognito を呼ばない。
 * Cognito のログインの回数の上限（120 件/秒）は、アカウントのユーザープールすべてで共有しているため
 */

export type DemoCredentials = { email: string; password: string };

/** 設定がなければ undefined。そのときは入口を出さない */
export function demoCredentials(): DemoCredentials | undefined {
  const email = process.env.AUTH_DEMO_USER_EMAIL;
  const password = process.env.AUTH_DEMO_USER_PASSWORD;
  return email && password ? { email, password } : undefined;
}

/**
 * 配るトークンが、これより短い残りしか持たなければ取り直す。受け取った人がすぐに更新することにならないように
 */
export const MIN_REMAINING_SECONDS = 15 * 60;

/**
 * トークンを使い回す。同時に求められても、取りに行くのは 1 回。
 *
 * 取り直しに失敗したときは、持っているものがまだ期限内ならそれを返す。Cognito が一時的に止まっても、入れる人を減らさない
 */
export function createDemoTokenCache(signIn: () => Promise<TokenSet>, now: () => number = Date.now) {
  let current: TokenSet | undefined;
  let pending: Promise<TokenSet> | undefined;

  const remaining = (tokens: TokenSet) => tokens.expiresAt - now() / 1000;

  return function tokens(): Promise<TokenSet> {
    if (current && remaining(current) >= MIN_REMAINING_SECONDS) return Promise.resolve(current);
    pending ??= signIn()
      .then((fresh) => {
        current = fresh;
        return fresh;
      })
      .catch((error: unknown) => {
        if (current && remaining(current) > 0) return current;
        throw error;
      })
      .finally(() => {
        pending = undefined;
      });
    return pending;
  };
}

/** サーバーで 1 つだけ持つ。Amplify の SSR では、サーバー（実行環境）ごとに 1 つになる */
export const demoTokens = createDemoTokenCache(() => {
  const credentials = demoCredentials();
  if (!credentials) return Promise.reject(new Error("デモのアカウントが設定されていません"));
  return signInWithPassword(credentials);
});

/**
 * Cognito にパスワードでログインする。スモークテストと同じ方式（`.github/scripts/smoke-token.sh`）。
 *
 * `InitiateAuth` は AWS の認証情報を使わない API なので、署名しない。
 * **エラーにはパスワードを入れない。** 返ってきたエラーの種類（`__type`）だけを残す
 */
export async function signInWithPassword(credentials: DemoCredentials): Promise<TokenSet> {
  const clientId = env("AUTH_CLIENT_ID");
  const response = await fetch(new URL(env("AUTH_ISSUER")).origin, {
    method: "POST",
    headers: {
      "content-type": "application/x-amz-json-1.1",
      "x-amz-target": "AWSCognitoIdentityProviderService.InitiateAuth",
    },
    body: JSON.stringify({
      AuthFlow: "USER_AUTH",
      ClientId: clientId,
      AuthParameters: {
        USERNAME: credentials.email,
        PASSWORD: credentials.password,
        SECRET_HASH: secretHash(credentials.email, clientId, env("AUTH_CLIENT_SECRET")),
        PREFERRED_CHALLENGE: "PASSWORD",
      },
    }),
  });
  const body = (await response.json().catch(() => ({}))) as {
    __type?: string;
    ChallengeName?: string;
    AuthenticationResult?: { AccessToken: string; IdToken?: string; RefreshToken?: string; ExpiresIn: number };
  };
  const result = body.AuthenticationResult;
  if (!response.ok || !result) {
    throw new Error(
      `デモのアカウントでログインできません (${response.status} ${body.__type ?? body.ChallengeName ?? ""})`,
    );
  }
  return {
    accessToken: result.AccessToken,
    idToken: result.IdToken,
    refreshToken: result.RefreshToken,
    expiresAt: Math.floor(Date.now() / 1000) + result.ExpiresIn,
  };
}

/** web のクライアントはシークレットを持つため、要求に利用者名 + クライアント ID の HMAC-SHA256 を添える */
export function secretHash(username: string, clientId: string, clientSecret: string): string {
  return createHmac("sha256", clientSecret)
    .update(username + clientId)
    .digest("base64");
}

function env(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} が設定されていません`);
  return value;
}
