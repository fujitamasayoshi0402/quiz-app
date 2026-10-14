import type { ZodType } from "zod";

/**
 * 生成されたクライアントが渡すオプション。`schema` は応答の Zod スキーマで、orval が操作ごとに埋める。
 */
export type ApiRequestInit = RequestInit & { schema?: ZodType };

/**
 * 生成されたクライアントが使う fetch。
 *
 * **認証情報をここで付けない。** 利用者の識別は Next.js の proxy が `/api` へのリクエストに付ける。
 * ブラウザのコードが認証ヘッダを組み立てないため、Phase 3 で Cognito に替えても変更は proxy だけで済む。
 *
 * **応答は Zod で検証する。** 定義とずれた応答は、画面の奥で undefined として壊れる前にここで落とす。
 */
export async function apiFetch<T>(url: string, init?: ApiRequestInit): Promise<T> {
  const { schema, ...requestInit } = init ?? {};
  const response = await fetch(url, requestInit);
  if (!response.ok) {
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
   * バックエンドに届かず、手前で返った 500 / 503 か。タスクが止まっているか、起動の途中にある（DEV-148）。
   *
   * 送り先のタスクがないとき、API Gateway は 500（`No target endpoints found`）か 503 を返す。どちらになるかは選べない。
   * ローカルでバックエンドを止めているときは、web の proxy が中継に失敗して 500 を返す。
   * バックエンドのエラーは、想定していない例外も含めて ProblemDetail で返るので、取り違えない
   */
  get serverMayBeStopped(): boolean {
    return this.problem === null && (this.status === 500 || this.status === 503);
  }
}

/**
 * バックエンドのエラーだけを読む。手前の API Gateway が返す応答（送り先がない 500 / 503、時間切れの 504 など）は、
 * JSON でも `{"message": ...}` という別の形で、ProblemDetail ではない。読むと、画面が理由のあるエラーとして扱い、
 * 「サーバーが止まっているか、起動の途中です」の案内を出さなくなる
 */
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
