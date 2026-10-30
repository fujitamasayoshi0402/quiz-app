import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import { getCompleteAttemptUrl, getListPlayableCategoriesUrl } from "@/lib/api/generated/endpoints";
import { AttemptResult, createTenantRequestSlugRegExp, PlayableCategoryResponse } from "@/lib/api/generated/model";
import { appOrigin } from "@/lib/auth/request";
import { readSession } from "@/lib/auth/session";
import { type ShareCard, sealShareCard } from "@/lib/share/token";
import { newTraceParent } from "@/lib/trace-context";

const Body = z.object({ slug: z.string().regex(createTenantRequestSlugRegExp), attemptId: z.uuid() });

/**
 * 結果の共有のリンクを作る（DEV-207。`lib/share/token.ts`）。web とモバイルの結果画面の「結果を共有する」が送る。
 *
 * - **点数は、ログインしている利用者のトークンで完了 API から取る。** 画面から送られるのは挑戦の ID だけで、点数は偽れない。
 *   完了 API は完了済みの挑戦に結果を返すだけなので、何度呼んでもよい。他人の挑戦は 404 になる
 * - POST だけを受ける。ほかのオリジンからの要求は、web の proxy が止める（`isSameOriginRequest`）
 * - トークンは、web のセッションか、`Authorization` から取る（{@link accessTokenOf}）
 */
export async function POST(request: NextRequest) {
  const body = Body.safeParse(await request.json().catch(() => undefined));
  if (!body.success) return problem(400, "共有する結果を指定してください");

  const accessToken = await accessTokenOf(request);
  if (!accessToken) {
    return problem(401, "ログインの期限が切れました。画面を読み込み直してから、もう一度お試しください");
  }

  const { slug, attemptId } = body.data;
  const call = (path: string, method: "GET" | "POST") =>
    fetch(new URL(path, process.env.API_ORIGIN ?? "http://localhost:8080"), {
      method,
      headers: { authorization: `Bearer ${accessToken}`, traceparent: newTraceParent() },
    });

  const completed = await call(getCompleteAttemptUrl(slug, attemptId), "POST");
  if (!completed.ok) return problem(completed.status, "結果を読み込めませんでした");
  const result = AttemptResult.parse(await completed.json());
  if (result.totalCount === 0) return problem(409, "問題のない結果は共有できません");

  const categories = await call(getListPlayableCategoriesUrl(slug), "GET");
  if (!categories.ok) return problem(categories.status, "カテゴリを読み込めませんでした");

  const card: ShareCard = {
    label: labelOf(result, z.array(PlayableCategoryResponse).parse(await categories.json())),
    correct: result.correctCount,
    total: result.totalCount,
  };
  const url = new URL(`/share/${await sealShareCard(card)}`, appOrigin(request));
  return NextResponse.json({ url: url.toString() });
}

/**
 * バックエンドを呼ぶトークン。
 *
 * - web … セッション（Cookie）のトークン。期限が切れていたら使わない。画面を読み込み直せば、proxy が更新する
 * - モバイル（DEV-212） … アプリが `Authorization: Bearer` で付けたトークン。アプリは API Gateway を直接呼ぶので、web のセッションを持たない。
 *   検証はバックエンドに任せる。ほかのサイトの画面は、利用者のトークンを知らず、このヘッダを付けて送らせることもできない（CORS を開けていない）
 */
async function accessTokenOf(request: NextRequest): Promise<string | undefined> {
  const session = await readSession(request.cookies);
  if (session) return session.expiresAt < Date.now() / 1000 ? undefined : session.accessToken;
  return /^Bearer (\S+)$/i.exec(request.headers.get("authorization") ?? "")?.[1];
}

/**
 * 範囲の名前。カテゴリを選ばずに始めた挑戦（今日の復習など）は、カテゴリがない。
 * 出題のあとに消えたカテゴリも、名前を出さない
 */
function labelOf(result: AttemptResult, categories: PlayableCategoryResponse[]): string {
  const category = categories.find((c) => c.id === result.categoryId);
  if (!category) return "クイズ";
  const difficulty = category.difficulties.find((d) => d.id === result.difficultyId);
  const range = difficulty ? difficulty.name : result.level != null ? `レベル ${result.level}` : undefined;
  return (range ? `${category.name}・${range}` : category.name).slice(0, 80);
}

/** バックエンドと同じ形（RFC 9457）で返す。画面の `ApiError` がそのまま読める */
function problem(status: number, detail: string) {
  return NextResponse.json(
    { type: "about:blank", status, detail },
    { status, headers: { "content-type": "application/problem+json" } },
  );
}
