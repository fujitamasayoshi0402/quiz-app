/**
 * 流し込みが呼ぶ管理の API（`/api/t/{slug}/admin/...`）。アクセストークンを `Authorization` に付けて、API を直接呼ぶ。
 *
 * 流量の上限（429）と、止まっている API の起動の途中（503）は、`Retry-After` の秒だけ待ってやり直す。
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
      if ((response.status === 429 || response.status === 503) && attempt < MAX_ATTEMPTS) {
        const seconds = Number(response.headers.get("Retry-After")) || 10;
        console.warn(`  ${response.status} のため、${seconds} 秒待ってやり直します（${method} ${path}）`);
        await new Promise((resolve) => setTimeout(resolve, seconds * 1_000));
        continue;
      }
      if (!response.ok) {
        // 応答は RFC 9457 の ProblemDetail。トークンは含まない
        throw new Error(`${method} ${path} が ${response.status} で失敗しました: ${await response.text()}`);
      }
      return (response.status === 204 ? undefined : await response.json()) as T;
    }
  }
}
