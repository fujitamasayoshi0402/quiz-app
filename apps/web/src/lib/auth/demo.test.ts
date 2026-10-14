import { afterEach, describe, expect, it, vi } from "vitest";
import type { TokenSet } from "./oidc";
import { MIN_REMAINING_SECONDS, createDemoTokenCache, secretHash, signInWithPassword } from "./demo";

const NOW = 1_800_000_000;

function tokensExpiringIn(seconds: number, name = "a"): TokenSet {
  return { accessToken: `access-${name}`, refreshToken: `refresh-${name}`, idToken: "id", expiresAt: NOW + seconds };
}

describe("createDemoTokenCache", () => {
  it("残りが十分あるうちは、Cognito を呼ばずに同じトークンを返す", async () => {
    const signIn = vi.fn().mockResolvedValue(tokensExpiringIn(3600));
    const tokens = createDemoTokenCache(signIn, () => NOW * 1000);

    await tokens();
    await tokens();
    const third = await tokens();

    expect(signIn).toHaveBeenCalledTimes(1);
    expect(third.accessToken).toBe("access-a");
  });

  it("残りが 15 分を切ったら取り直す", async () => {
    const signIn = vi
      .fn()
      .mockResolvedValueOnce(tokensExpiringIn(MIN_REMAINING_SECONDS - 1, "old"))
      .mockResolvedValueOnce(tokensExpiringIn(3600, "new"));
    const tokens = createDemoTokenCache(signIn, () => NOW * 1000);

    await tokens();
    const second = await tokens();

    expect(signIn).toHaveBeenCalledTimes(2);
    expect(second.accessToken).toBe("access-new");
  });

  it("同時に求められても、取りに行くのは 1 回", async () => {
    let resolve: (tokens: TokenSet) => void = () => undefined;
    const signIn = vi.fn(() => new Promise<TokenSet>((r) => (resolve = r)));
    const tokens = createDemoTokenCache(signIn, () => NOW * 1000);

    const all = Promise.all([tokens(), tokens(), tokens()]);
    resolve(tokensExpiringIn(3600));

    expect((await all).map((t) => t.accessToken)).toEqual(["access-a", "access-a", "access-a"]);
    expect(signIn).toHaveBeenCalledTimes(1);
  });

  it("取り直しに失敗しても、持っているものが期限内なら返す。期限が切れていれば失敗させる", async () => {
    let now = NOW;
    const signIn = vi
      .fn()
      .mockResolvedValueOnce(tokensExpiringIn(60, "old"))
      .mockRejectedValue(new Error("TooManyRequestsException"));
    const tokens = createDemoTokenCache(signIn, () => now * 1000);

    await tokens();
    expect((await tokens()).accessToken).toBe("access-old");

    now = NOW + 61;
    await expect(tokens()).rejects.toThrow("TooManyRequestsException");
  });

  it("一度も取れていなければ、失敗をそのまま返し、次は取り直す", async () => {
    const signIn = vi.fn().mockRejectedValueOnce(new Error("失敗")).mockResolvedValueOnce(tokensExpiringIn(3600));
    const tokens = createDemoTokenCache(signIn, () => NOW * 1000);

    await expect(tokens()).rejects.toThrow("失敗");
    expect((await tokens()).accessToken).toBe("access-a");
  });
});

describe("secretHash", () => {
  it("利用者名 + クライアント ID の HMAC-SHA256 を Base64 にする（openssl で求めた値と同じ）", () => {
    expect(secretHash("demo@example.com", "client-1", "secret-1")).toBe("A1gk4ahmddVPj1US+axOWWddinS2Lxk2tcO0Vmx2FSc=");
  });
});

describe("signInWithPassword", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  function stubEnv() {
    vi.stubEnv("AUTH_ISSUER", "https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_pool");
    vi.stubEnv("AUTH_CLIENT_ID", "client-1");
    vi.stubEnv("AUTH_CLIENT_SECRET", "secret-1");
  }

  it("Cognito の InitiateAuth を USER_AUTH で呼び、トークンを返す", async () => {
    stubEnv();
    const fetch = vi.fn().mockResolvedValue(
      Response.json({
        AuthenticationResult: { AccessToken: "access", IdToken: "id", RefreshToken: "refresh", ExpiresIn: 3600 },
      }),
    );
    vi.stubGlobal("fetch", fetch);

    const tokens = await signInWithPassword({ email: "demo@example.com", password: "pw" });

    expect(tokens).toMatchObject({ accessToken: "access", idToken: "id", refreshToken: "refresh" });
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://cognito-idp.ap-northeast-1.amazonaws.com");
    expect(new Headers(init.headers).get("x-amz-target")).toBe("AWSCognitoIdentityProviderService.InitiateAuth");
    expect(JSON.parse(init.body as string)).toMatchObject({
      AuthFlow: "USER_AUTH",
      ClientId: "client-1",
      AuthParameters: { USERNAME: "demo@example.com", PASSWORD: "pw", PREFERRED_CHALLENGE: "PASSWORD" },
    });
  });

  it("失敗したときのエラーに、パスワードを入れない", async () => {
    stubEnv();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(Response.json({ __type: "NotAuthorizedException" }, { status: 400 })),
    );

    const signingIn = signInWithPassword({ email: "demo@example.com", password: "the-password" });

    await expect(signingIn).rejects.toThrow("NotAuthorizedException");
    await expect(signingIn).rejects.not.toThrow("the-password");
  });
});
