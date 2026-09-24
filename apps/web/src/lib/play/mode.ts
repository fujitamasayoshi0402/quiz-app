/**
 * フィードバックの方式。サーバーは区別しない（要件定義「API は共通にする」）。
 *
 * - learn … 学習モード。1 問ごとに正誤と解説を出す（既定）
 * - exam  … 模試モード。回答中は正誤を伏せ、結果画面でまとめて振り返る
 */
export type FeedbackMode = "learn" | "exam";

export function parseMode(value: string | string[] | undefined | null): FeedbackMode {
  return value === "exam" ? "exam" : "learn";
}

const key = (attemptId: string) => `quiz-mode:${attemptId}`;

/**
 * 方式はサーバーに保存しないため、再開したときに分からなくなる。
 * この端末で始めた挑戦に限り、ブラウザに覚えておく。別の端末で再開したときは学習モードになる。
 */
export function rememberMode(attemptId: string, mode: FeedbackMode) {
  try {
    localStorage.setItem(key(attemptId), mode);
  } catch {
    // 保存できない環境では既定の学習モードで再開するだけなので、無視してよい
  }
}

export function recallMode(attemptId: string): FeedbackMode {
  try {
    return parseMode(localStorage.getItem(key(attemptId)));
  } catch {
    return "learn";
  }
}
