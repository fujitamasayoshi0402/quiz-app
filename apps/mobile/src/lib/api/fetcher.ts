import type { ZodType } from "zod";
import { accessToken, forgetSession } from "@/lib/auth/client";
import { currentEnvironment } from "@/lib/current-environment";

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
  // 呼び終えるまでに切り替わっても、始めたつなぐ先のトークンで呼び、そのつなぐ先のログインの状態だけを捨てる（DEV-208）
  const environment = currentEnvironment();
  const token = await accessToken(environment);
  const headers = new Headers(requestInit.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(`${environment.apiOrigin}${url}`, { ...requestInit, headers });
  if (!response.ok) {
    // トークンを付けたのに断られた。失効しているので、ログインし直してもらう
    if (response.status === 401 && token) await forgetSession(environment);
    throw new ApiError(response.status, await readProblem(response));
  }
  // 本文のない応答は null にする。TanStack Query は undefined をデータとして受け付けない
  if (response.status === 204) {
    return null as T;
  }
  const body: unknown = await response.json();
  return (schema ? schema.parse(body) : body) as T;
}

/**
 * 解説図の API を呼び、署名付き URL へのリダイレクトをたどる（ADR-0017、ADR-0032）。
 *
 * 図の API は、トークンを付けて呼ぶ必要がある。画像の部品はトークンを付けられないため、ここでたどった先を渡す。
 * `HEAD` は、たどった先の URL だけを知りたいときに使う。PDF（20 MB まで）を開く前に、中身まで落とさない
 */
export async function followFigure(path: string, method: "GET" | "HEAD"): Promise<Response> {
  const environment = currentEnvironment();
  const token = await accessToken(environment);
  const response = await fetch(`${environment.apiOrigin}${path}`, {
    method,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) {
    if (response.status === 401 && token) await forgetSession(environment);
    throw new ApiError(response.status, await readProblem(response));
  }
  return response;
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
export async function readProblem(response: Response): Promise<Problem | null> {
  if (!response.headers.get("content-type")?.startsWith("application/problem+json")) {
    return null;
  }
  try {
    return (await response.json()) as Problem;
  } catch {
    return null;
  }
}
