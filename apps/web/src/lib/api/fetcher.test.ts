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

  // 夜間の停止中は、API Gateway が送り先のないまま 503 を返す。理由のないエラーとして扱い、画面に止まっている旨を出す
  it("API Gateway が返した JSON は、バックエンドのエラーとして読まない", async () => {
    respond(503, "application/json", { message: "Service Unavailable" });

    const error = await failure();
    expect(error.status).toBe(503);
    expect(error.problem).toBeNull();
  });
});
