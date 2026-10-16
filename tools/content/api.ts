/**
 * 流し込みが呼ぶ管理の API（`/api/t/{slug}/admin/...`）。アクセストークンを `Authorization` に付けて、API を直接呼ぶ。
 *
 * 流量の上限（429）と、アプリが返した 503 は、`Retry-After` の秒だけ待ってやり直す。
 * API Gateway が返した 500 / 503（タスクが止まっている）は、待たずに、起こすように伝えて止める。
 * 図の作成と一括取り込みは重い操作で、利用者ごとに 1 分 20 件まで（開発ガイドライン「ECS」）
 */
export type CategorySummary = { id: string; name: string };
export type DifficultySummary = { id: string; name: string; level: number };
export type FigureSummary = { id: string; kind: string; createdAt: string };
export type QuizItem = {
  id: string;
  categoryId: string;
  difficultyId: string;
  question: string;
  explanation: string | null;
  status: string;
  choices: { id: string; body: string; isCorrect: boolean }[];
};

const MAX_ATTEMPTS = 8;

// Node は型を消すだけで動かす（変換しない）。コンストラクタの引数でフィールドを作る書き方は使えない
export class Api {
  readonly origin: string;
  readonly slug: string;
  readonly #token: string;

  constructor(origin: string, slug: string, token: string) {
    this.origin = origin.replace(/\/$/, "");
    this.slug = slug;
    this.#token = token;
  }

  categories = () => this.request<CategorySummary[]>("GET", "categories");
  createCategory = (name: string, description?: string) =>
    this.request<CategorySummary>("POST", "categories", { name, description });
  difficulties = (categoryId: string) => this.request<DifficultySummary[]>("GET", `categories/${categoryId}/difficulties`);
  createDifficulty = (categoryId: string, body: { name: string; level: number; description?: string }) =>
    this.request<DifficultySummary>("POST", `categories/${categoryId}/difficulties`, body);

  figures = () => this.request<FigureSummary[]>("GET", "figures");
  figureSource = (id: string) => this.request<{ source: string }>("GET", `figures/${id}/source`).then((r) => r.source);
  createFigure = (source: string, svg: string) => this.request<{ id: string }>("POST", "figures", { source, svg });

  quizzes = (categoryId: string) =>
    this.request<QuizItem[]>("GET", `quizzes?categoryId=${encodeURIComponent(categoryId)}`);
  importQuizzes = (quizzes: unknown[]) =>
    this.request<{ importedCount: number }>("POST", "quizzes/import", { quizzes });
  updateQuiz = (id: string, body: unknown) => this.request<QuizItem>("PUT", `quizzes/${id}`, body);
  deleteQuiz = (id: string) => this.request<void>("DELETE", `quizzes/${id}`);

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const url = `${this.origin}/api/t/${this.slug}/admin/${path}`;
    for (let attempt = 1; ; attempt++) {
      const response = await fetch(url, {
        method,
        headers: {
          Authorization: `Bearer ${this.#token}`,
          ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const problem = response.headers.get("Content-Type")?.startsWith("application/problem+json") ?? false;
      // アプリのエラーは、すべて ProblemDetail で返る。そうでない 500 / 503 は、API Gateway が返したもので、
      // 送り先のタスクがない（dev は使われなくなると止まる。DEV-153）。待っても起きないので、起こすように伝えて止める
      if ((response.status === 500 || response.status === 503) && !problem) {
        throw new Error(
          "API が止まっているか、起動の途中です。画面の「サーバーを起動する」を押すか、" +
            "aws ecs update-service --cluster quiz-app-dev --service quiz-service --desired-count 1 で起こしてから、流し直してください",
        );
      }
      if ((response.status === 429 || response.status === 503) && attempt < MAX_ATTEMPTS) {
        const seconds = Number(response.headers.get("Retry-After")) || 10;
        console.warn(`  ${response.status} のため、${seconds} 秒待ってやり直します（${method} ${path}）`);
        await new Promise((resolve) => setTimeout(resolve, seconds * 1_000));
        continue;
      }
      if (!response.ok) {
        // 応答は RFC 9457 の ProblemDetail。トークンは含まない
        const text = await response.text();
        throw new ApiError(`${method} ${path} が ${response.status} で失敗しました: ${text}`, problem ? parse(text) : undefined);
      }
      return (response.status === 204 ? undefined : await response.json()) as T;
    }
  }
}

/** API が返したエラー。ProblemDetail なら、中身（取り込めない行など）を持つ */
export class ApiError extends Error {
  readonly problem: Record<string, unknown> | undefined;

  constructor(message: string, problem: Record<string, unknown> | undefined) {
    super(message);
    this.problem = problem;
  }
}

function parse(text: string): Record<string, unknown> | undefined {
  try {
    return JSON.parse(text) as Record<string, unknown>;
  } catch {
    return undefined;
  }
}
