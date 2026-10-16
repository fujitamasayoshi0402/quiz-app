import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiFetch } from "./fetcher";

function respond(status: number, contentType: string, body: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify(body), { status, headers: { "content-type": contentType } })),
  );
}

async function failure(): Promise<ApiError> {
  const error: unknown = await apiFetch("/api/me/tenants").catch((e: unknown) => e);
  expect(error).toBeInstanceOf(ApiError);
  return error as ApiError;
}

describe("apiFetch のエラー", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("バックエンドの ProblemDetail を読む", async () => {
    respond(401, "application/problem+json", {
      title: "認証が必要です",
      detail: "利用者を特定できません",
      status: 401,
    });

    const error = await failure();
    expect(error.status).toBe(401);
    expect(error.problem?.title).toBe("認証が必要です");
  });

  // 夜間の停止中は、API Gateway が送り先のないまま 500 か 503 を返す。理由のないエラーとして扱い、画面に止まっている旨を出す
  it.each([
    [500, "Internal Server Error"],
    [503, "Service Unavailable"],
  ])(
    "API Gateway が返した %i は、バックエンドのエラーとして読まず、止まっているかもしれないとみなす",
    async (status, message) => {
      respond(status, "application/json", { message });

      const error = await failure();
      expect(error.status).toBe(status);
      expect(error.problem).toBeNull();
      expect(error.serverMayBeStopped).toBe(true);
    },
  );

  it("バックエンドが返した 500 と 503 は、止まっているとみなさない", async () => {
    respond(500, "application/problem+json", { title: "サーバー内部エラー", status: 500 });
    expect((await failure()).serverMayBeStopped).toBe(false);

    respond(503, "application/problem+json", { title: "通知の設定を保存できません", status: 503 });
    expect((await failure()).serverMayBeStopped).toBe(false);
  });

  it("手前で返った 504 は、止まっているとみなさない", async () => {
    respond(504, "application/json", { message: "Endpoint request timed out" });

    expect((await failure()).serverMayBeStopped).toBe(false);
  });
});
