import type { AttemptHistoryItem } from "@/lib/api/generated/model";

/**
 * 履歴の画面で出す言葉と数（web の `lib/accuracy.ts`、`lib/play/scope.ts`、`components/history` と同じ）。
 */

/** 率を出すのに要る回答の数。1 回まちがえただけで 0% と出ると、実際より苦手に見えてしまう */
export const MIN_ANSWERS = 5;

/** カテゴリ別の正答率（0〜100 の整数）。回答が少なければ null */
export function rateOf(answered: number, correct: number): number | null {
  if (answered < MIN_ANSWERS) return null;
  return Math.round((correct / answered) * 100);
}

/** 挑戦の正答率。結果画面と同じく、出題数を分母にする。解かずに終えた問題は不正解と同じ扱い */
export function attemptRate({ totalCount, correctCount }: Pick<AttemptHistoryItem, "totalCount" | "correctCount">) {
  return totalCount === 0 ? 0 : Math.round((correctCount / totalCount) * 100);
}

/**
 * 何を解いたか。名前が返らないのは、そのカテゴリや難易度がいま出題されていないとき（削除・非公開）。
 * 名前は挑戦に記録していないので、消えたものの名前は出せない
 */
export function attemptTitle(item: AttemptHistoryItem): string {
  const category = item.categoryId == null ? "すべてのカテゴリ" : (item.categoryName ?? "出題されていないカテゴリ");
  const range =
    item.difficultyId != null
      ? (item.difficultyName ?? "出題されていない難易度")
      : item.level != null
        ? `レベル ${item.level} のすべて`
        : "すべての難易度";
  return `${category}・${range}`;
}

/** 出題対象の名前。アプリで選べるのは「すべて」だけだが、web で始めた挑戦も履歴に並ぶ */
const SCOPE_LABELS: Record<string, string> = {
  all: "すべて",
  unanswered: "未回答を優先",
  unanswered_only: "未回答のみ",
  incorrect: "間違えた問題",
  bookmarked: "あとで見直す問題",
  review: "今日の復習",
};

export function scopeLabel(value: string): string {
  return SCOPE_LABELS[value] ?? value;
}
