import type { QuizListItemResponse, SearchQuizzesParams } from "@/lib/api/generated/model";
import { rateOf } from "@/lib/history";

/**
 * 管理の画面のクイズの一覧（DEV-216、ADR-0037。web の `quiz-list.tsx` と `lib/admin/accuracy.ts` から写した）。
 * React と端末には触れない
 */

/** 「すべて」を表す値 */
export const ALL = "all";

export const STATUS_OPTIONS = [
  { value: ALL, label: "すべての状態" },
  { value: "published", label: "公開" },
  { value: "draft", label: "下書き" },
];

export const ORIGIN_OPTIONS = [
  { value: ALL, label: "すべての作成元" },
  { value: "ai", label: "AI が作ったもの" },
  { value: "human", label: "人が作ったもの" },
];

export const ORDER_OPTIONS = [
  { value: "registered", label: "登録順" },
  { value: "accuracy", label: "正答率の低い順" },
];

export type QuizFilters = {
  categoryId: string;
  difficultyId: string;
  status: string;
  origin: string;
};

export const NO_FILTERS: QuizFilters = { categoryId: ALL, difficultyId: ALL, status: ALL, origin: ALL };

/** 絞り込みを API の条件にする。「すべて」は送らない */
export function toSearchParams({ categoryId, difficultyId, status, origin }: QuizFilters): SearchQuizzesParams {
  return {
    categoryId: categoryId === ALL ? undefined : categoryId,
    difficultyId: difficultyId === ALL ? undefined : difficultyId,
    status: status === ALL ? undefined : status,
    // AI が技術ニュースから作ったものは、出典の URL を持つ（ADR-0031）
    aiGenerated: origin === ALL ? undefined : origin === "ai",
  };
}

/** いくつの条件で絞り込んでいるか。畳んでいるときに、絞り込み中だと分かるように */
export function activeFilterCount(filters: QuizFilters): number {
  return Object.values(filters).filter((value) => value !== ALL).length;
}

type Counts = Pick<QuizListItemResponse, "answerCount" | "correctCount">;

/** 正答率は、回答が少なければ率の代わりにそう出す。1 回まちがえただけで 0% と出さない */
export function accuracyLabel({ answerCount, correctCount }: Counts): string {
  const rate = rateOf(answerCount, correctCount);
  return rate === null ? "回答が少ない" : `正答率 ${rate}%`;
}

/**
 * 正答率の低い順に並べる。率が同じものと、回答が少なくて率のないものは、元の並び（登録順）のまま。
 * 率のないものは最後に置く。比べようがなく、先に置くと間違えやすい問題が埋もれる。
 */
export function sortByAccuracy<T extends Counts>(quizzes: readonly T[]): T[] {
  const rank = (quiz: T) => rateOf(quiz.answerCount, quiz.correctCount) ?? 101;
  return [...quizzes].sort((a, b) => rank(a) - rank(b));
}

export function statusLabel(status: string): string {
  return status === "published" ? "公開" : "下書き";
}

/** 管理の画面の入口を出すか。出さないのは見た目のためで、権限は API が確かめる */
export function canManage(role: string | undefined): boolean {
  return role === "admin";
}
