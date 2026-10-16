import { MIN_ANSWERS } from "@/lib/accuracy";
import type { QuizDetailResponse } from "@/lib/api/generated/model";

/**
 * 編集画面に出す、選択肢ごとに選ばれた数（DEV-170）。どの誤答に引っかかっているかを見て、問題文や選択肢を直す。
 *
 * 割合の分母は、消した選択肢への回答も含めたすべての回答。回答が少ないうちは割合を出さない（`lib/accuracy.ts`）。
 */
export type ChoiceAnswers = {
  /** 欄ごとの、選ばれた数と割合（0〜100 の整数。回答が少なければ null）。選択肢のない欄は null */
  slots: ({ count: number; share: number | null } | null)[];
  total: number;
  removed: number;
};

export function choiceAnswersOf(quiz: Pick<QuizDetailResponse, "choices" | "removedChoiceAnswerCount">, slots: number) {
  const total = quiz.choices.reduce((sum, choice) => sum + choice.answerCount, 0) + quiz.removedChoiceAnswerCount;
  const share = (count: number) => (total < MIN_ANSWERS ? null : Math.round((count / total) * 100));
  return {
    slots: Array.from({ length: slots }, (_, index) => {
      const choice = quiz.choices[index];
      return choice ? { count: choice.answerCount, share: share(choice.answerCount) } : null;
    }),
    total,
    removed: quiz.removedChoiceAnswerCount,
  } satisfies ChoiceAnswers;
}
