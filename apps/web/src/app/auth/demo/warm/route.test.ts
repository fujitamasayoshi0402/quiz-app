import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { stubAuthEnv } from "@/test-support/auth";

/** 前に呼んだ時刻はモジュールに残るので、テストごとに読み直す */
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

  it("API の起こす口を呼び、何も返さない", async () => {
    const fetch = vi.fn<(input: string | URL) => Promise<Response>>(async () => new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetch);
    const POST = await load();

    const response = await POST();

    expect(response.status).toBe(204);
    expect(fetch.mock.calls.map(([url]) => url.toString())).toEqual(["https://api.example/api/warm-up"]);
  });

  it("デモのアカウントが設定されていなければ 404 で、何も呼ばない", async () => {
    vi.stubEnv("AUTH_DEMO_USER_PASSWORD", "");
    const fetch = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetch);
    const POST = await load();

    const response = await POST();

    expect(response.status).toBe(404);
    expect(fetch).not.toHaveBeenCalled();
  });
});
