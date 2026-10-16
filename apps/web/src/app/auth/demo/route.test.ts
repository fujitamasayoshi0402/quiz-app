import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cookieJar, idToken, revokedTokens, stubAuthEnv, stubCognito } from "@/test-support/auth";

const signedIn = () =>
  Response.json({
    AuthenticationResult: {
      AccessToken: "demo-access",
      IdToken: idToken({ sub: "demo-sub", email: "demo@example.com" }),
      RefreshToken: "demo-refresh",
      ExpiresIn: 3600,
    },
  });

function request(cookie?: string) {
  return new NextRequest("http://localhost:3000/auth/demo", {
    method: "POST",
    headers: cookie ? { cookie } : {},
  });
}

/** トークンの使い回しと発行者の設定はモジュールに残るので、テストごとに読み直す */
async function load() {
  vi.resetModules();
  const route = await import("./route");
  const session = await import("@/lib/auth/session");
  return { POST: route.POST, ...session };
}

describe("POST /auth/demo", () => {
  beforeEach(() => {
    stubAuthEnv();
    vi.stubEnv("AUTH_DEMO_USER_EMAIL", "demo@example.com");
    vi.stubEnv("AUTH_DEMO_USER_PASSWORD", "demo-password");
    vi.spyOn(console, "error").mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("デモのアカウントでセッションを作り、トップへ移す。セッションには共有の印が付く", async () => {
    stubCognito(signedIn);
    const { POST, readSession, isSharedSession } = await load();

    const response = await POST(request());

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe("http://localhost:3000/");
    expect(await readSession(response.cookies)).toMatchObject({ sub: "demo-sub", accessToken: "demo-access" });
    expect(await isSharedSession(response.cookies)).toBe(true);
  });

  it("続けて押されても、Cognito にログインしに行くのは 1 回", async () => {
    const fetch = stubCognito(signedIn);
    const { POST } = await load();

    for (let i = 0; i < 5; i++) await POST(request());

    const initiateAuth = fetch.mock.calls.filter(([url]) => url.toString().startsWith("https://cognito-idp."));
    expect(initiateAuth).toHaveLength(1);
  });

  it("Cognito がログインを拒んだら、セッションを作らずにログインの画面へ戻す", async () => {
    stubCognito(() => Response.json({ __type: "NotAuthorizedException" }, { status: 400 }));
    const { POST, readSession } = await load();

    const response = await POST(request());

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe("http://localhost:3000/login?error=demo");
    expect(await readSession(response.cookies)).toBeUndefined();
  });

  it("デモのアカウントが設定されていなければ 404", async () => {
    vi.stubEnv("AUTH_DEMO_USER_PASSWORD", "");
    const fetch = stubCognito(signedIn);
    const { POST } = await load();

    const response = await POST(request());

    expect(response.status).toBe(404);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("自分のアカウントでログインしていたら、そのリフレッシュトークンを失効させてから入り直す", async () => {
    const fetch = stubCognito(signedIn);
    const { POST, writeSession } = await load();
    const jar = cookieJar();
    await writeSession(jar, { accessToken: "mine", refreshToken: "my-refresh", expiresAt: 0 }, { sub: "me" }, false);

    await POST(request(jar.header()));

    expect(revokedTokens(fetch)).toEqual(["my-refresh"]);
  });

  it("デモのセッションのまま押しても、共有のリフレッシュトークンは失効させない", async () => {
    const fetch = stubCognito(signedIn);
    const { POST, writeSession } = await load();
    const jar = cookieJar();
    const shared = { accessToken: "demo-access", refreshToken: "demo-refresh", expiresAt: 0 };
    await writeSession(jar, shared, { sub: "demo-sub" }, false, { shared: true });

    await POST(request(jar.header()));

    expect(revokedTokens(fetch)).toEqual([]);
  });
});
