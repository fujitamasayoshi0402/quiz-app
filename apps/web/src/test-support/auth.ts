import { vi } from "vitest";

/**
 * 認証のルートを確かめるテストの道具。Cognito への要求を、URL ごとに決めた応答で返す
 */
export const ISSUER = "https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_pool";
export const REVOCATION_ENDPOINT = "https://auth.example/oauth2/revoke";

export function stubAuthEnv() {
  vi.stubEnv("AUTH_ISSUER", ISSUER);
  vi.stubEnv("AUTH_CLIENT_ID", "client-1");
  vi.stubEnv("AUTH_CLIENT_SECRET", "secret-1");
  vi.stubEnv("AUTH_SESSION_SECRET", "session-secret-for-tests");
}

/** 署名のない ID トークン。web は表示にしか使わず、署名を確かめない（`identityOf`） */
export function idToken(claims: Record<string, string>): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
  return `${encode({ alg: "none" })}.${encode(claims)}.`;
}

/** 発行者の設定、失効、InitiateAuth に答える fetch。InitiateAuth の応答は引数で決める */
export function stubCognito(initiateAuth: () => Response) {
  // 失効の要求の本文を、呼び出しの記録（2 つ目の引数）から読む
  const fetch = vi.fn<(input: string | URL, init?: RequestInit) => Promise<Response>>(async (input) => {
    const url = input.toString();
    if (url.endsWith("/.well-known/openid-configuration")) {
      return Response.json({
        authorization_endpoint: "https://auth.example/oauth2/authorize",
        token_endpoint: "https://auth.example/oauth2/token",
        revocation_endpoint: REVOCATION_ENDPOINT,
      });
    }
    if (url === REVOCATION_ENDPOINT) return new Response(null, { status: 200 });
    if (url === new URL(ISSUER).origin) return initiateAuth();
    throw new Error(`想定していない要求: ${url}`);
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

export const revokedTokens = (fetch: ReturnType<typeof stubCognito>) =>
  fetch.mock.calls
    .filter(([url]) => url.toString() === REVOCATION_ENDPOINT)
    .map(([, init]) => new URLSearchParams(init?.body as string).get("token"));

/** Cookie をためる入れ物。`writeSession` で前のセッションを作るのに使う */
export function cookieJar() {
  const values = new Map<string, string>();
  return {
    set: (name: string, value: string) => values.set(name, value),
    get: (name: string) => (values.has(name) ? { value: values.get(name)! } : undefined),
    header: () => [...values].map(([name, value]) => `${name}=${value}`).join("; "),
  };
}
