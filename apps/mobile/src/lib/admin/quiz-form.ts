import { z } from "zod";
import {
  type QuizResponse,
  type SaveQuizRequest,
  SaveQuizRequest as SaveQuizRequestSchema,
  saveQuizRequestChoicesItemBodyMax,
} from "@/lib/api/generated/model";

/** バックエンドの Quiz の選択肢数の範囲（DEV-243）。公開時は 2〜6 個、新規作成の初期値は 4 個 */
export const MIN_CHOICE_COUNT = 2;
export const MAX_CHOICE_COUNT = 6;
export const CHOICE_COUNT = 4;

export type QuizStatus = "draft" | "published";

/**
 * クイズの編集フォーム（DEV-227、DEV-243。web の `lib/admin/quiz-form.ts` から写した。ADR-0037）。
 * **定義に表れない規則を変えるときは、web と両方を直す。**
 *
 * **生成したスキーマを土台にする。** 文字数の上限や ID の形式は OpenAPI 定義から来るため、
 * バックエンドの制約を変えれば、ここも作り直しで追従する。
 * 足しているのは、定義に表れない規則だけ。最終的な判定はバックエンドが行う。
 *
 * - 空白だけの問題文を拒む（定義は最短 0 文字）
 * - 公開するには、選択肢 2〜6 個・正解 1 個以上・解説が必要（ドメインの不変条件。下書きでは求めない）
 */
export const QuizFormSchema = z
  .object({
    // 未選択は空文字列。UUID の形式エラーではなく「選んでください」と伝える
    categoryId: z.string().min(1, "カテゴリを選んでください").pipe(SaveQuizRequestSchema.shape.categoryId),
    difficultyId: z.string().min(1, "難易度を選んでください").pipe(SaveQuizRequestSchema.shape.difficultyId),
    question: SaveQuizRequestSchema.shape.question.trim().min(1, "問題文を入力してください"),
    choices: z.array(z.string().max(saveQuizRequestChoicesItemBodyMax)).min(MIN_CHOICE_COUNT).max(MAX_CHOICE_COUNT),
    // 欄ごとの、直す前の選択肢の ID。保存しても ID を保ち、選ばれた数を引き継ぐ（DEV-170）。画面には出さない
    choiceIds: z.array(z.string().nullable()).min(MIN_CHOICE_COUNT).max(MAX_CHOICE_COUNT),
    correctIndexes: z.array(
      z
        .number()
        .int()
        .min(0)
        .max(MAX_CHOICE_COUNT - 1),
    ),
    explanation: z.string(),
    status: z.enum(["draft", "published"]),
  })
  .superRefine((form, ctx) => {
    for (const index of form.correctIndexes) {
      if (!form.choices[index]?.trim()) {
        ctx.addIssue({
          code: "custom",
          path: ["choices", index],
          message: "正解にした選択肢を入力してください",
        });
      }
    }
    if (form.status !== "published") return;

    const filledCount = form.choices.filter((body) => body.trim() !== "").length;
    form.choices.forEach((body, index) => {
      if (!body.trim()) {
        ctx.addIssue({
          code: "custom",
          path: ["choices", index],
          message:
            filledCount < MIN_CHOICE_COUNT
              ? `公開するには選択肢を ${MIN_CHOICE_COUNT} つ以上入力してください`
              : "公開するには空欄の選択肢を埋めるか、欄を消してください",
        });
      }
    });
    if (form.correctIndexes.length === 0) {
      ctx.addIssue({ code: "custom", path: ["correctIndexes"], message: "公開するには正解を選んでください" });
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
  choiceIds: Array.from({ length: CHOICE_COUNT }, () => null),
  correctIndexes: [],
  explanation: "",
  status: "draft",
};

export function toQuizForm(quiz: QuizResponse): QuizFormValues {
  const bodies = quiz.choices.map((choice) => choice.body);
  const ids = quiz.choices.map((choice) => choice.id);
  const slotCount = Math.max(MIN_CHOICE_COUNT, Math.min(MAX_CHOICE_COUNT, bodies.length || CHOICE_COUNT));
  const correctIndexes = quiz.choices.flatMap((choice, index) => (choice.isCorrect ? [index] : []));
  return {
    categoryId: quiz.categoryId,
    difficultyId: quiz.difficultyId,
    question: quiz.question,
    choices: [...bodies, ...Array.from({ length: Math.max(0, slotCount - bodies.length) }, () => "")],
    choiceIds: [...ids, ...Array.from({ length: Math.max(0, slotCount - ids.length) }, () => null)],
    correctIndexes,
    explanation: quiz.explanation,
    status: quiz.status === "published" ? "published" : "draft",
  };
}

/**
 * フォームの値を API の形にする。**空欄の選択肢は送らない。**
 * 下書きではすべて埋まっていなくてよいが、バックエンドは空の選択肢そのものを拒むため。
 *
 * 欄にもともとあった選択肢の ID を添える。文言を直しても、その欄の選択肢として選ばれた数を引き継ぐ。
 * 欄を空にして保存すると、その選択肢は消える。
 */
export function toSaveQuizRequest(form: QuizFormValues): SaveQuizRequest {
  const correctSet = new Set(form.correctIndexes);
  return {
    categoryId: form.categoryId,
    difficultyId: form.difficultyId,
    question: form.question,
    explanation: form.explanation,
    status: form.status,
    choices: form.choices
      .map((body, index) => ({
        id: form.choiceIds[index] ?? undefined,
        body: body.trim(),
        isCorrect: correctSet.has(index),
      }))
      .filter((choice) => choice.body !== ""),
  };
}

/** 入力欄ごとの誤り。キーは欄の位置（`question`、`choices.0` など）。web では react-hook-form が持つものを、自分で持つ */
export type QuizFormErrors = Partial<Record<string, string>>;

/** 押したほうの状態で検証する。公開の条件は下書きより厳しい。問題がなければ API の形を返す */
export function validateQuizForm(
  form: QuizFormValues,
  status: QuizStatus,
): { ok: true; data: SaveQuizRequest } | { ok: false; errors: QuizFormErrors } {
  const result = QuizFormSchema.safeParse({ ...form, status });
  if (result.success) return { ok: true, data: toSaveQuizRequest(result.data) };
  const errors: QuizFormErrors = {};
  for (const issue of result.error.issues) {
    // 同じ欄に誤りが重なったら、最初のものだけを出す
    errors[issue.path.join(".")] ??= issue.message;
  }
  return { ok: false, errors };
}
