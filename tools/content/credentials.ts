import { type CredentialStore, validateProfile } from "./keychain.ts";

export type LoginConfig = { region: string; clientId: string; apiOrigin: string; email: string };
type Session = LoginConfig & { version: 1; accessToken: string; refreshToken: string; expiresAt: number };
type AuthenticationResult = { AccessToken: string; RefreshToken?: string; ExpiresIn: number };

export function validateConfig(config: LoginConfig): void {
  if (!/^[a-z]{2}(-[a-z]+)+-\d$/.test(config.region) || !/^[a-zA-Z0-9_]{1,128}$/.test(config.clientId)) {
    throw new Error("Cognito のリージョンと公開クライアント ID を確認してください");
  }
  let url: URL;
  try {
    url = new URL(config.apiOrigin);
  } catch {
    throw new Error("API のオリジンが不正です");
  }
  if (url.protocol !== "https:" || url.username || url.password || url.origin !== config.apiOrigin) {
    throw new Error("API はパスや認証情報を含まない https のオリジンで指定してください");
  }
  if (!config.email.trim()) throw new Error("メールアドレスを指定してください");
}

class AuthFailure extends Error {
  readonly rejected: boolean;
  constructor(message: string, rejected: boolean) {
    super(message);
    this.rejected = rejected;
  }
}

/** 環境ごとの認証状態。パスワードは保存せず、Cognito のトークンをキーチェーンに置く。 */
export class Credentials {
  private store: CredentialStore;
  private request: typeof fetch;
  private now: () => number;
  constructor(store: CredentialStore, request: typeof fetch = fetch, now = Date.now) {
    this.store = store;
    this.request = request;
    this.now = now;
  }

  private read(profile: string): Session | undefined {
    validateProfile(profile);
    const value = this.store.read(profile);
    if (value === undefined) return undefined;
    try {
      const session = JSON.parse(value) as Session;
      validateConfig(session);
      if (
        session.version !== 1 ||
        typeof session.accessToken !== "string" ||
        !session.accessToken ||
        typeof session.refreshToken !== "string" ||
        !session.refreshToken ||
        !Number.isFinite(session.expiresAt)
      )
        throw new Error();
      return session;
    } catch {
      throw new Error("保存済み認証を読めません。auth.ts login で登録し直してください");
    }
  }

  private async call(config: LoginConfig, action: string, body: unknown): Promise<unknown> {
    let response: Response;
    try {
      response = await this.request(`https://cognito-idp.${config.region}.amazonaws.com/`, {
        method: "POST",
        headers: {
          "Content-Type": "application/x-amz-json-1.1",
          "X-Amz-Target": `AWSCognitoIdentityProviderService.${action}`,
        },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(30_000),
      });
    } catch {
      throw new Error("Cognito と通信できませんでした。保存済み認証は保持しています");
    }
    let result: unknown;
    try {
      result = await response.json();
    } catch {
      if (response.ok && action === "RevokeToken") return {};
      throw new Error(`Cognito の応答を読めません（HTTP ${response.status}）`);
    }
    if (!response.ok) {
      const rawType = (result as { __type?: unknown })?.__type;
      const type = typeof rawType === "string" ? rawType.split("#").at(-1) : undefined;
      const rejected = type === "NotAuthorizedException" || type === "UserNotFoundException";
      throw new AuthFailure(`Cognito が認証を受け付けませんでした（HTTP ${response.status}）`, rejected);
    }
    return result;
  }

  private session(config: LoginConfig, body: unknown, previousRefresh?: string): Session {
    const result = (body as { AuthenticationResult?: AuthenticationResult })?.AuthenticationResult;
    if (
      !result?.AccessToken ||
      typeof result.AccessToken !== "string" ||
      !Number.isFinite(result.ExpiresIn) ||
      result.ExpiresIn <= 0
    ) {
      throw new Error("認証が完了していません。追加の認証操作や Cognito の設定を確認してください");
    }
    const refreshToken = result.RefreshToken ?? previousRefresh;
    if (typeof refreshToken !== "string" || !refreshToken) throw new Error("更新トークンを取得できませんでした");
    return {
      ...config,
      version: 1,
      accessToken: result.AccessToken,
      refreshToken,
      expiresAt: this.now() + result.ExpiresIn * 1000,
    };
  }

  async login(profile: string, config: LoginConfig, password: string): Promise<void> {
    validateProfile(profile);
    validateConfig(config);
    const body = await this.call(config, "InitiateAuth", {
      ClientId: config.clientId,
      AuthFlow: "USER_AUTH",
      AuthParameters: { USERNAME: config.email, PASSWORD: password, PREFERRED_CHALLENGE: "PASSWORD" },
    });
    this.store.write(profile, JSON.stringify(this.session(config, body)));
  }

  async access(profile: string): Promise<{ apiOrigin: string; accessToken: string }> {
    let session = this.read(profile);
    if (!session) throw new Error(`${profile} の保存済み認証がありません。auth.ts login でログインしてください`);
    if (session.expiresAt <= this.now() + 60_000) {
      try {
        const body = await this.call(session, "GetTokensFromRefreshToken", {
          ClientId: session.clientId,
          RefreshToken: session.refreshToken,
        });
        session = this.session(session, body, session.refreshToken);
        this.store.write(profile, JSON.stringify(session));
      } catch (error) {
        if (error instanceof AuthFailure && error.rejected) {
          this.store.delete(profile);
          throw new Error(`${profile} の認証が期限切れか失効しています。auth.ts login でログインし直してください`);
        }
        throw error;
      }
    }
    return { apiOrigin: session.apiOrigin, accessToken: session.accessToken };
  }

  status(profile: string): { email: string; apiOrigin: string; expiresAt: number } | undefined {
    const session = this.read(profile);
    if (!session) return undefined;
    return { email: session.email, apiOrigin: session.apiOrigin, expiresAt: session.expiresAt };
  }

  async logout(profile: string): Promise<void> {
    const session = this.read(profile);
    if (!session) return;
    try {
      await this.call(session, "RevokeToken", { ClientId: session.clientId, Token: session.refreshToken });
    } catch (error) {
      if (!(error instanceof AuthFailure && error.rejected)) throw error;
    }
    this.store.delete(profile);
  }
}
