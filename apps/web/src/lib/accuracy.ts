/**
 * 正答率の出し方。管理画面のクイズの一覧（DEV-158）と、履歴のカテゴリ別の正答率（DEV-169）で同じにする。
 *
 * 回答が少ないうちは率を出さない。1 回まちがえただけで 0% と出ると、実際より苦手に見えてしまう。
 */

/** 率を出すのに要る回答の数。これより少なければ「回答が少ない」とする */
export const MIN_ANSWERS = 5;

/** 正答率（0〜100 の整数）。回答が少なければ null */
export function rateOf(answered: number, correct: number): number | null {
  if (answered < MIN_ANSWERS) return null;
  return Math.round((correct / answered) * 100);
}
