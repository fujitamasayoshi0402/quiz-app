import type { PlayableCategoryResponse } from "@/lib/api/generated/model";

/**
 * 出題範囲。カテゴリの中で「難易度 1 つ」か「同じレベルの難易度すべて」か「すべて」を選ぶ（web の `lib/play/range.ts` と同じ）。
 *
 * レベルで選べるようにしているのは、同じレベルに複数の難易度が並ぶ分野があるため。
 */
export type Range = { kind: "all" } | { kind: "difficulty"; id: string } | { kind: "level"; level: number };

export type Option = { value: string; label: string; hint?: string };

export type RangeOption = Option & { range: Range };

export function rangeOptions(category: PlayableCategoryResponse): RangeOption[] {
  const all: RangeOption = {
    value: "all",
    label: "すべての難易度",
    hint: `${category.quizCount} 問`,
    range: { kind: "all" },
  };

  const difficulties = category.difficulties.map<RangeOption>((difficulty) => ({
    value: `difficulty:${difficulty.id}`,
    label: difficulty.name,
    hint: `レベル ${difficulty.level}・${difficulty.quizCount} 問`,
    range: { kind: "difficulty", id: difficulty.id },
  }));

  // 難易度が 1 つしかないレベルは、難易度を選ぶのと同じなので出さない。
  // Map.groupBy は Hermes にないため、自分でまとめる
  const byLevel = new Map<number, PlayableCategoryResponse["difficulties"]>();
  for (const difficulty of category.difficulties) {
    byLevel.set(difficulty.level, [...(byLevel.get(difficulty.level) ?? []), difficulty]);
  }
  const levels = [...byLevel.entries()]
    .filter(([, members]) => members.length > 1)
    .map<RangeOption>(([level, members]) => ({
      value: `level:${level}`,
      label: `レベル ${level} のすべて`,
      hint: `${members.map((member) => member.name).join("・")}・${members.reduce((sum, m) => sum + m.quizCount, 0)} 問`,
      range: { kind: "level", level },
    }));

  return [all, ...difficulties, ...levels];
}

/** 出題 API の条件に変換する */
export function toCriteria(categoryId: string, range: Range) {
  return {
    categoryId,
    difficultyId: range.kind === "difficulty" ? range.id : undefined,
    level: range.kind === "level" ? range.level : undefined,
  };
}

/**
 * フィードバックの方式。サーバーは区別しない（要件定義「API は共通にする」）。
 *
 * - learn … 学習モード。1 問ごとに正誤と解説を出す（既定）
 * - exam  … 模試モード。回答中は正誤を伏せ、結果画面でまとめて振り返る
 *
 * web のテンポモード（スワイプ）は、アプリにはまだない
 */
export type FeedbackMode = "learn" | "exam";

export function parseMode(value: string | string[] | undefined): FeedbackMode {
  return value === "exam" ? "exam" : "learn";
}
