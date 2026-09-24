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
}

async function readProblem(response: Response): Promise<Problem | null> {
  try {
    return (await response.json()) as Problem;
  } catch {
    return null;
  }
}
