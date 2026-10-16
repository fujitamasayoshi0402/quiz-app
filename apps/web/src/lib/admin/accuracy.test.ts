import { describe, expect, it } from "vitest";
import { sortByAccuracy } from "./accuracy";

const quiz = (id: string, answerCount: number, correctCount: number) => ({ id, answerCount, correctCount });

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
