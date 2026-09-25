/**
 * 認証基盤（Cognito）との OIDC のやり取り。**サーバーでだけ使う。**
 *
 * 認可コードフロー（PKCE つき）で、クライアントのシークレットを持つ（ADR-0016）。
 * トークンはこのサーバーが受け取り、ブラウザには渡さない。
 * 標準の OIDC だけを使い、Cognito に固有なのはログアウトの URL だけにする。認証基盤を替えるときに直す場所を減らす。
 */

type ProviderMetadata = {
  authorization_endpoint: string;
  token_endpoint: string;
  revocation_endpoint?: string;
};

export type TokenSet = {
  accessToken: string;
  /** 更新のときは返らない（Cognito はリフレッシュトークンを入れ替えない） */
  refreshToken?: string;
  idToken?: string;
  /** アクセストークンの期限（UNIX 秒） */
  expiresAt: number;
};

/** ID トークンから取る、画面に出す利用者の情報 */
export type Identity = { sub: string; email?: string };

export const SCOPES = "openid email";

function env(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} が設定されていません`);
  return value;
}

export const clientId = () => env("AUTH_CLIENT_ID");

let metadata: Promise<ProviderMetadata> | undefined;

/** 発行者の設定（discovery）。最初に使うときに一度だけ読む */
export function providerMetadata(): Promise<ProviderMetadata> {
  metadata ??= fetch(`${env("AUTH_ISSUER")}/.well-known/openid-configuration`).then(async (response) => {
    if (!response.ok) {
      metadata = undefined;
      throw new Error(`発行者の設定を読めません (${response.status})`);
    }
    return (await response.json()) as ProviderMetadata;
  });
  return metadata;
}

export async function exchangeCode(code: string, redirectUri: string, codeVerifier: string): Promise<TokenSet> {
  return requestToken({ grant_type: "authorization_code", code, redirect_uri: redirectUri, code_verifier: codeVerifier });
}

export async function refreshTokens(refreshToken: string): Promise<TokenSet> {
  return requestToken({ grant_type: "refresh_token", refresh_token: refreshToken });
}

/** リフレッシュトークンを失効させる。失敗してもログアウトは続ける */
export async function revoke(refreshToken: string): Promise<void> {
  const { revocation_endpoint: endpoint } = await providerMetadata();
  if (!endpoint) return;
  await fetch(endpoint, {
    method: "POST",
    headers: clientHeaders(),
    body: new URLSearchParams({ token: refreshToken }),
  }).catch(() => undefined);
}

/**
 * 認証基盤のセッションも終える URL。ここは Cognito に固有（`/logout`）。
 * 終えないと、次の「ログイン」で認証基盤のセッションが残ったまま、利用者を選び直せない。
 */
export async function logoutUrl(returnTo: string): Promise<string> {
  const { authorization_endpoint: authorize } = await providerMetadata();
  const url = new URL("/logout", authorize);
  url.searchParams.set("client_id", clientId());
  url.searchParams.set("logout_uri", returnTo);
  return url.toString();
}

/**
 * ID トークンの中身を読む。**署名は確かめない。**
 * TLS でトークンのエンドポイントから直接受け取ったものに限って使い、画面の表示にだけ使う。認可には使わない
 */
export function identityOf(idToken: string | undefined): Identity | undefined {
  const payload = idToken?.split(".")[1];
  if (!payload) return undefined;
  const claims = JSON.parse(Buffer.from(payload, "base64url").toString()) as { sub?: string; email?: string };
  return claims.sub ? { sub: claims.sub, email: claims.email } : undefined;
}

function clientHeaders(): HeadersInit {
  const credentials = Buffer.from(`${clientId()}:${env("AUTH_CLIENT_SECRET")}`).toString("base64");
  return { "content-type": "application/x-www-form-urlencoded", authorization: `Basic ${credentials}` };
}

async function requestToken(params: Record<string, string>): Promise<TokenSet> {
  const { token_endpoint: endpoint } = await providerMetadata();
  const response = await fetch(endpoint, {
    method: "POST",
    headers: clientHeaders(),
    body: new URLSearchParams({ client_id: clientId(), ...params }),
  });
  if (!response.ok) {
    throw new Error(`トークンを取れません (${response.status})`);
  }
  const body = (await response.json()) as {
    access_token: string;
    refresh_token?: string;
    id_token?: string;
    expires_in: number;
  };
  return {
    accessToken: body.access_token,
    refreshToken: body.refresh_token,
    idToken: body.id_token,
    expiresAt: Math.floor(Date.now() / 1000) + body.expires_in,
  };
}
