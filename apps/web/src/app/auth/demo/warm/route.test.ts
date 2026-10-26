import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { stubAuthEnv, stubCognito } from "@/test-support/auth";

const signedIn = () => Response.json({ AuthenticationResult: { AccessToken: "demo-access", ExpiresIn: 3600 } });

/** トークンと前に呼んだ時刻はモジュールに残るので、テストごとに読み直す */
async function load() {
  vi.resetModules();
  return (await import("./route")).POST;
}

describe("POST /auth/demo/warm", () => {
  beforeEach(() => {
    stubAuthEnv();
    vi.stubEnv("API_ORIGIN", "https://api.example");
    vi.stubEnv("AUTH_DEMO_USER_EMAIL", "demo@example.com");
    vi.stubEnv("AUTH_DEMO_USER_PASSWORD", "demo-password");
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("デモのアカウントで API を呼び、何も返さない", async () => {
    const cognito = stubCognito(signedIn);
    const fetch = vi.fn(async (input: string | URL, init?: RequestInit) =>
      input.toString().startsWith("https://api.example") ? new Response("[]") : cognito(input, init),
    );
    vi.stubGlobal("fetch", fetch);
    const POST = await load();

    const response = await POST();

    expect(response.status).toBe(204);
    expect(fetch.mock.calls.map(([url]) => url.toString())).toContain("https://api.example/api/me/tenants");
  });

  it("デモのアカウントが設定されていなければ 404 で、何も呼ばない", async () => {
    vi.stubEnv("AUTH_DEMO_USER_PASSWORD", "");
    const fetch = stubCognito(signedIn);
    const POST = await load();

    const response = await POST();

    expect(response.status).toBe(404);
    expect(fetch).not.toHaveBeenCalled();
  });
});
