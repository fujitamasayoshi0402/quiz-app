/**
 * 管理画面のクイズの一覧に出す正答率（DEV-158）。
 *
 * 回答が少ないうちは率を出さない。1 回まちがえただけで 0% と出ると、間違えやすい問題に見えてしまう。
 */

/** 率を出すのに要る回答の数。これより少なければ「回答が少ない」とする */
export const MIN_ANSWERS = 5;

type Counts = { answerCount: number; correctCount: number };

/** 正答率（0〜100 の整数）。回答が少なければ null */
export function accuracyOf({ answerCount, correctCount }: Counts): number | null {
  if (answerCount < MIN_ANSWERS) return null;
  return Math.round((correctCount / answerCount) * 100);
}

/**
 * 正答率の低い順に並べる。率が同じものと、回答が少なくて率のないものは、元の並び（登録順）のまま。
 * 率のないものは最後に置く。比べようがなく、先に置くと間違えやすい問題が埋もれる。
 */
export function sortByAccuracy<T extends Counts>(quizzes: readonly T[]): T[] {
  // 率のないものは 101 として最後に回す。Infinity にすると、同士の差が NaN になり並びが崩れる
  const rank = (quiz: T) => accuracyOf(quiz) ?? 101;
  return [...quizzes].sort((a, b) => rank(a) - rank(b));
}
