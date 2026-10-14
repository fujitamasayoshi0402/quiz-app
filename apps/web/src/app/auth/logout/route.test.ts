import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cookieJar, revokedTokens, stubAuthEnv, stubCognito } from "@/test-support/auth";

async function load() {
  vi.resetModules();
  const route = await import("./route");
  const session = await import("@/lib/auth/session");
  return { POST: route.POST, writeSession: session.writeSession };
}

describe("POST /auth/logout", () => {
  beforeEach(stubAuthEnv);

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  async function logout(shared: boolean) {
    const fetch = stubCognito(() => new Response(null, { status: 500 }));
    const { POST, writeSession } = await load();
    const jar = cookieJar();
    const tokens = { accessToken: "access", refreshToken: "refresh", expiresAt: 0 };
    await writeSession(jar, tokens, { sub: "someone" }, false, { shared });

    const response = await POST(
      new NextRequest("http://localhost:3000/auth/logout", { method: "POST", headers: { cookie: jar.header() } }),
    );
    return { response, revoked: revokedTokens(fetch) };
  }

  it("リフレッシュトークンを失効させ、Cookie を消す", async () => {
    const { response, revoked } = await logout(false);

    expect(revoked).toEqual(["refresh"]);
    expect(response.cookies.get("qa_session")?.value).toBe("");
  });

  it("共有のデモのアカウントのセッションでは、失効させずに Cookie だけを消す（ADR-0029）", async () => {
    const { response, revoked } = await logout(true);

    expect(revoked).toEqual([]);
    expect(response.cookies.get("qa_session")?.value).toBe("");
  });
});
