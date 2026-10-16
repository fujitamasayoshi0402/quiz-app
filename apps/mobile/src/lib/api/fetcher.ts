import type { ZodType } from "zod";
import { accessToken, forgetSession } from "@/lib/auth/client";
import { config } from "@/lib/config";

/**
 * 生成されたクライアントが渡すオプション。`schema` は応答の Zod スキーマで、orval が操作ごとに埋める。
 */
export type ApiRequestInit = RequestInit & { schema?: ZodType };

/**
 * 生成されたクライアントが使う fetch（ADR-0032）。
 *
 * web と違い、proxy を通さずに API Gateway を直接呼び、アクセストークンをここで付ける。
 * **応答は Zod で検証する。** 定義とずれた応答は、画面の奥で undefined として壊れる前にここで落とす
 */
export async function apiFetch<T>(url: string, init?: ApiRequestInit): Promise<T> {
  const { schema, ...requestInit } = init ?? {};
  const token = await accessToken();
  const headers = new Headers(requestInit.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(`${config.apiOrigin}${url}`, { ...requestInit, headers });
  if (!response.ok) {
    // トークンを付けたのに断られた。失効しているので、ログインし直してもらう
    if (response.status === 401 && token) await forgetSession();
    throw new ApiError(response.status, await readProblem(response));
  }
  // 本文のない応答は null にする。TanStack Query は undefined をデータとして受け付けない
  if (response.status === 204) {
    return null as T;
  }
  const body: unknown = await response.json();
  return (schema ? schema.parse(body) : body) as T;
}

/** バックエンドは RFC 9457 の ProblemDetail でエラーを返す。 */
export type Problem = {
  title?: string;
  detail?: string;
  [key: string]: unknown;
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem | null,
  ) {
    super(problem?.detail ?? `API エラー (${status})`);
    this.name = "ApiError";
  }

  /**
   * バックエンドに届かず、手前で返った 500 / 503 か。dev のタスクが止まっているか、起動の途中にある（DEV-148、DEV-153）。
   * バックエンドのエラーは ProblemDetail で返るので、取り違えない
   */
  get serverMayBeStopped(): boolean {
    return this.problem === null && (this.status === 500 || this.status === 503);
  }
}

/** バックエンドのエラーだけを読む。API Gateway が返す応答（`{"message": ...}`）は ProblemDetail ではない */
async function readProblem(response: Response): Promise<Problem | null> {
  if (!response.headers.get("content-type")?.startsWith("application/problem+json")) {
    return null;
  }
  try {
    return (await response.json()) as Problem;
  } catch {
    return null;
  }
}
