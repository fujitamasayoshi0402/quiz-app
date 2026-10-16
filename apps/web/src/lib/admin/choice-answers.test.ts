import { describe, expect, it } from "vitest";
import { MIN_ANSWERS } from "@/lib/accuracy";
import { choiceAnswersOf } from "./choice-answers";

const choice = (answerCount: number) => ({ id: crypto.randomUUID(), body: "選択肢", isCorrect: false, answerCount });

describe("choiceAnswersOf", () => {
  it("割合の分母は、消した選択肢への回答も含めたすべての回答", () => {
    const answers = choiceAnswersOf({ choices: [choice(6), choice(3), choice(0)], removedChoiceAnswerCount: 3 }, 4);

    expect(answers.total).toBe(12);
    expect(answers.removed).toBe(3);
    expect(answers.slots).toEqual([{ count: 6, share: 50 }, { count: 3, share: 25 }, { count: 0, share: 0 }, null]);
  });

  it("回答が少ないうちは、数だけで割合を出さない", () => {
    const answers = choiceAnswersOf({ choices: [choice(MIN_ANSWERS - 2), choice(1)], removedChoiceAnswerCount: 0 }, 2);

    expect(answers.slots).toEqual([
      { count: MIN_ANSWERS - 2, share: null },
      { count: 1, share: null },
    ]);
  });
});
