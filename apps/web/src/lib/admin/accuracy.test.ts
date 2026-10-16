import { describe, expect, it } from "vitest";
import { MIN_ANSWERS, accuracyOf, sortByAccuracy } from "./accuracy";

const quiz = (id: string, answerCount: number, correctCount: number) => ({ id, answerCount, correctCount });

describe("accuracyOf", () => {
  it("回答が少ないうちは率を出さない", () => {
    expect(accuracyOf(quiz("q", 0, 0))).toBeNull();
    expect(accuracyOf(quiz("q", MIN_ANSWERS - 1, 0))).toBeNull();
  });

  it("回答が揃えば、整数に丸めた率を出す", () => {
    expect(accuracyOf(quiz("q", MIN_ANSWERS, 0))).toBe(0);
    expect(accuracyOf(quiz("q", 6, 4))).toBe(67);
    expect(accuracyOf(quiz("q", 8, 8))).toBe(100);
  });
});

describe("sortByAccuracy", () => {
  it("率の低い順。同じ率は元の並びのまま、率のないものは最後", () => {
    const quizzes = [
      quiz("few", 1, 0),
      quiz("high", 10, 9),
      quiz("low-1", 10, 2),
      quiz("none", 0, 0),
      quiz("low-2", 5, 1),
    ];

    expect(sortByAccuracy(quizzes).map((it) => it.id)).toEqual(["low-1", "low-2", "high", "few", "none"]);
  });

  it("元の配列は並べ替えない", () => {
    const quizzes = [quiz("high", 10, 9), quiz("low", 10, 1)];

    sortByAccuracy(quizzes);

    expect(quizzes.map((it) => it.id)).toEqual(["high", "low"]);
  });
});
