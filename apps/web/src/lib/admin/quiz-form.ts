import { z } from "zod";
import {
  type QuizResponse,
  type SaveQuizRequest,
  SaveQuizRequest as SaveQuizRequestSchema,
  saveQuizRequestChoicesItemBodyMax,
} from "@/lib/api/generated/model";

/** バックエンドの Quiz.CHOICE_COUNT と同じ。4 択であることは OpenAPI 定義に表れない */
export const CHOICE_COUNT = 4;

export type QuizStatus = "draft" | "published";

/**
 * クイズの編集フォーム。
 *
 * **生成したスキーマを土台にする。** 文字数の上限や ID の形式は OpenAPI 定義から来るため、
 * バックエンドの制約を変えれば、ここも作り直しで追従する。
 * 足しているのは、定義に表れない規則だけ。最終的な判定はバックエンドが行う。
 *
 * - 空白だけの問題文を拒む（定義は最短 0 文字）
 * - 公開するには、選択肢 4 つ・正解 1 つ・解説が必要（ドメインの不変条件。下書きでは求めない）
 */
export const QuizFormSchema = z
  .object({
    // 未選択は空文字列。UUID の形式エラーではなく「選んでください」と伝える
    categoryId: z.string().min(1, "カテゴリを選んでください").pipe(SaveQuizRequestSchema.shape.categoryId),
    difficultyId: z.string().min(1, "難易度を選んでください").pipe(SaveQuizRequestSchema.shape.difficultyId),
    question: SaveQuizRequestSchema.shape.question.trim().min(1, "問題文を入力してください"),
    choices: z.array(z.string().max(saveQuizRequestChoicesItemBodyMax)).length(CHOICE_COUNT),
    correctIndex: z.number().int().min(0).max(CHOICE_COUNT - 1).nullable(),
    explanation: z.string(),
    status: z.enum(["draft", "published"]),
  })
  .superRefine((form, ctx) => {
    if (form.correctIndex !== null && !form.choices[form.correctIndex]?.trim()) {
      ctx.addIssue({
        code: "custom",
        path: ["choices", form.correctIndex],
        message: "正解にした選択肢を入力してください",
      });
    }
    if (form.status !== "published") return;

    form.choices.forEach((body, index) => {
      if (!body.trim()) {
        ctx.addIssue({ code: "custom", path: ["choices", index], message: "公開するには 4 つとも入力してください" });
      }
    });
    if (form.correctIndex === null) {
      ctx.addIssue({ code: "custom", path: ["correctIndex"], message: "公開するには正解を選んでください" });
    }
    if (!form.explanation.trim()) {
      ctx.addIssue({ code: "custom", path: ["explanation"], message: "公開するには解説を入力してください" });
    }
  });

export type QuizFormValues = z.infer<typeof QuizFormSchema>;

export const emptyQuizForm: QuizFormValues = {
  categoryId: "",
  difficultyId: "",
  question: "",
  choices: Array.from({ length: CHOICE_COUNT }, () => ""),
  correctIndex: null,
  explanation: "",
  status: "draft",
};

export function toQuizForm(quiz: QuizResponse): QuizFormValues {
  const bodies = quiz.choices.map((choice) => choice.body);
  const correctIndex = quiz.choices.findIndex((choice) => choice.isCorrect);
  return {
    categoryId: quiz.categoryId,
    difficultyId: quiz.difficultyId,
    question: quiz.question,
    choices: [...bodies, ...Array.from({ length: CHOICE_COUNT - bodies.length }, () => "")],
    correctIndex: correctIndex === -1 ? null : correctIndex,
    explanation: quiz.explanation,
    status: quiz.status === "published" ? "published" : "draft",
  };
}

/**
 * フォームの値を API の形にする。**空欄の選択肢は送らない。**
 * 下書きでは 4 つ埋まっていなくてよいが、バックエンドは空の選択肢そのものを拒むため。
 */
export function toSaveQuizRequest(form: QuizFormValues): SaveQuizRequest {
  return {
    categoryId: form.categoryId,
    difficultyId: form.difficultyId,
    question: form.question,
    explanation: form.explanation,
    status: form.status,
    choices: form.choices
      .map((body, index) => ({ body: body.trim(), isCorrect: index === form.correctIndex }))
      .filter((choice) => choice.body !== ""),
  };
}
