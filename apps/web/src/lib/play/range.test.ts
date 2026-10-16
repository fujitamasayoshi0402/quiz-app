import { describe, expect, it } from "vitest";
import type { BookmarkedQuiz, PlayableCategoryResponse } from "@/lib/api/generated/model";
import { countInRange } from "./range";

const difficulty = (id: string, level: number) => ({ id, level, name: id, quizCount: 1 });

/** SAA と DVA が同じレベル 2 に並ぶ。CLF はレベル 1 */
const aws: PlayableCategoryResponse = {
  id: "aws",
  name: "AWS",
  quizCount: 4,
  difficulties: [difficulty("clf", 1), difficulty("saa", 2), difficulty("dva", 2)],
};

const bookmarks: BookmarkedQuiz[] = [
  { quizId: "q1", categoryId: "aws", difficultyId: "clf" },
  { quizId: "q2", categoryId: "aws", difficultyId: "saa" },
  { quizId: "q3", categoryId: "aws", difficultyId: "dva" },
  { quizId: "q4", categoryId: "geo", difficultyId: "capital" },
];

describe("countInRange", () => {
  it("すべての難易度では、そのカテゴリの印をすべて数える。ほかのカテゴリは数えない", () => {
    expect(countInRange(bookmarks, aws, { kind: "all" })).toBe(3);
  });

  it("難易度を選ぶと、その難易度だけを数える", () => {
    expect(countInRange(bookmarks, aws, { kind: "difficulty", id: "saa" })).toBe(1);
  });

  it("レベルを選ぶと、同じレベルの難易度をまとめて数える", () => {
    expect(countInRange(bookmarks, aws, { kind: "level", level: 2 })).toBe(2);
  });
});
