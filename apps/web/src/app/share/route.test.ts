import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cookieJar, stubAuthEnv } from "@/test-support/auth";

const slug = "demo";
const attemptId = "6f1c1c55-8a3e-4c43-9a35-2d3a4b3c2d1e";
const categoryId = "0b6a8a4c-7d6e-4b8e-9a52-1f2e3d4c5b6a";
const difficultyId = "1c7b9b5d-8e7f-4c9f-8b63-2a3f4e5d6c7b";

const result = {
  id: attemptId,
  status: "completed",
  categoryId,
  difficultyId,
  level: null,
  totalCount: 10,
  answeredCount: 10,
  correctCount: 8,
  results: [],
};

const categories = [
  {
    id: categoryId,
    name: "世界の地理",
    quizCount: 10,
    difficulties: [{ id: difficultyId, name: "基礎", level: 1, quizCount: 10 }],
  },
];

async function load() {
  vi.resetModules();
  return {
    POST: (await import("./route")).POST,
    ...(await import("@/lib/auth/session")),
    ...(await import("@/lib/share/token")),
  };
}

/** 完了 API とカテゴリの一覧に答える fetch。完了 API の応答は引数で替える */
function stubApi(completed: () => Response = () => Response.json(result)) {
  const fetch = vi.fn<(input: string | URL, init?: RequestInit) => Promise<Response>>(async (input) => {
    const url = input.toString();
    if (url === `https://api.example/api/t/${slug}/play/attempts/${attemptId}/complete`) return completed();
    if (url === `https://api.example/api/t/${slug}/play/categories`) return Response.json(categories);
    return new Response(null, { status: 404 });
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

async function post(body: unknown, { expiresAt = Date.now() / 1000 + 3600, signedIn = true, authorization = "" } = {}) {
  const { POST, writeSession } = await load();
  const jar = cookieJar();
  if (signedIn) await writeSession(jar, { accessToken: "access", expiresAt }, { sub: "someone" }, false);
  const headers: Record<string, string> = { cookie: jar.header(), "content-type": "application/json" };
  if (authorization) headers.authorization = authorization;
  return POST(new NextRequest("http://localhost:3000/share", { method: "POST", headers, body: JSON.stringify(body) }));
}

describe("POST /share", () => {
  beforeEach(() => {
    stubAuthEnv();
    vi.stubEnv("API_ORIGIN", "https://api.example");
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("完了 API の点数とカテゴリの名前で、共有のリンクを作る", async () => {
    const fetch = stubApi();

    const response = await post({ slug, attemptId });

    expect(response.status).toBe(200);
    const { url } = (await response.json()) as { url: string };
    expect(url).toMatch(/^http:\/\/localhost:3000\/share\/[\w.-]+$/);
    const { openShareCard } = await load();
    expect(await openShareCard(url.split("/share/")[1])).toEqual({ label: "世界の地理・基礎", correct: 8, total: 10 });
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: "POST", headers: { authorization: "Bearer access" } });
  });

  it("画面から送られた点数は使わない", async () => {
    stubApi();

    const response = await post({ slug, attemptId, correct: 10, total: 10 });

    const { url } = (await response.json()) as { url: string };
    const { openShareCard } = await load();
    expect((await openShareCard(url.split("/share/")[1]))?.correct).toBe(8);
  });

  it("カテゴリを選ばずに始めた挑戦（今日の復習など）は「クイズ」と出す", async () => {
    stubApi(() => Response.json({ ...result, categoryId: null, difficultyId: null }));

    const { url } = (await (await post({ slug, attemptId })).json()) as { url: string };

    const { openShareCard } = await load();
    expect((await openShareCard(url.split("/share/")[1]))?.label).toBe("クイズ");
  });

  it("他人の挑戦や破棄した挑戦は、完了 API の状態をそのまま返す", async () => {
    stubApi(() => new Response(null, { status: 404 }));

    expect((await post({ slug, attemptId })).status).toBe(404);
  });

  it("ログインしていないか、トークンの期限が切れていれば 401 で、API を呼ばない", async () => {
    const fetch = stubApi();

    expect((await post({ slug, attemptId }, { signedIn: false })).status).toBe(401);
    expect((await post({ slug, attemptId }, { expiresAt: Date.now() / 1000 - 1 })).status).toBe(401);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("モバイルは、Authorization のトークンで完了 API を呼ぶ（web のセッションを持たない）", async () => {
    const fetch = stubApi();

    const response = await post({ slug, attemptId }, { signedIn: false, authorization: "Bearer mobile-access" });

    expect(response.status).toBe(200);
    expect(fetch.mock.calls[0][1]).toMatchObject({ headers: { authorization: "Bearer mobile-access" } });
  });

  it("Bearer の形でないものは、トークンとして使わない", async () => {
    const fetch = stubApi();

    expect((await post({ slug, attemptId }, { signedIn: false, authorization: "Basic dXNlcjpwYXNz" })).status).toBe(
      401,
    );
    expect((await post({ slug, attemptId }, { signedIn: false, authorization: "Bearer a b" })).status).toBe(401);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("挑戦の ID やテナントの形が違えば 400 で、API を呼ばない（パスに混ぜさせない）", async () => {
    const fetch = stubApi();

    expect((await post({ slug: "../admin", attemptId })).status).toBe(400);
    expect((await post({ slug, attemptId: "1/../../x" })).status).toBe(400);
    expect(fetch).not.toHaveBeenCalled();
  });
});
