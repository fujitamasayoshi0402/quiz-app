import type { PlayableCategoryResponse } from "@/lib/api/generated/model";

/**
 * 出題範囲。カテゴリの中で「難易度 1 つ」か「同じレベルの難易度すべて」か「すべて」を選ぶ。
 *
 * レベルで選べるようにしているのは、同じレベルに複数の難易度が並ぶ分野があるため
 * （AWS のアソシエイト級に SAA / DVA / SOA が並ぶ、など）。
 */
export type Range = { kind: "all" } | { kind: "difficulty"; id: string } | { kind: "level"; level: number };

export type RangeOption = { value: string; label: string; hint: string; range: Range; quizCount: number };

export function rangeOptions(category: PlayableCategoryResponse): RangeOption[] {
  const all: RangeOption = {
    value: "all",
    label: "すべての難易度",
    hint: `${category.quizCount} 問`,
    range: { kind: "all" },
    quizCount: category.quizCount,
  };

  const difficulties = category.difficulties.map<RangeOption>((difficulty) => ({
    value: `difficulty:${difficulty.id}`,
    label: difficulty.name,
    hint: `レベル ${difficulty.level}・${difficulty.quizCount} 問`,
    range: { kind: "difficulty", id: difficulty.id },
    quizCount: difficulty.quizCount,
  }));

  // 難易度が 1 つしかないレベルは、難易度を選ぶのと同じなので出さない
  const byLevel = Map.groupBy(category.difficulties, (difficulty) => difficulty.level);
  const levels = [...byLevel.entries()]
    .filter(([, members]) => members.length > 1)
    .map<RangeOption>(([level, members]) => {
      const quizCount = members.reduce((sum, member) => sum + member.quizCount, 0);
      return {
        value: `level:${level}`,
        label: `レベル ${level} のすべて`,
        hint: `${members.map((member) => member.name).join("・")}・${quizCount} 問`,
        range: { kind: "level", level },
        quizCount,
      };
    });

  return [all, ...difficulties, ...levels];
}

/** 出題 API の条件に変換する。 */
export function toCriteria(categoryId: string, range: Range) {
  return {
    categoryId,
    difficultyId: range.kind === "difficulty" ? range.id : undefined,
    level: range.kind === "level" ? range.level : undefined,
  };
}
