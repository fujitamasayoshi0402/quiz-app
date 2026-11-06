import { describe, expect, it } from "vitest";
import { type QuizFormValues, emptyQuizForm, toQuizForm, toSaveQuizRequest, validateQuizForm } from "./quiz-form";

const CATEGORY = "11111111-1111-4111-8111-111111111111";
const DIFFICULTY = "22222222-2222-4222-8222-222222222222";
const CHOICE = "33333333-3333-4333-8333-333333333333";

const filled: QuizFormValues = {
  ...emptyQuizForm,
  categoryId: CATEGORY,
  difficultyId: DIFFICULTY,
  question: "問題",
  choices: ["A", "B", "C", "D"],
  correctIndex: 1,
  explanation: "解説",
};

describe("validateQuizForm", () => {
  it("下書きは、選択肢と正解と解説がなくても保存できる", () => {
    const result = validateQuizForm(
      { ...filled, choices: ["A", "", "", ""], correctIndex: null, explanation: "" },
      "draft",
    );
    expect(result.ok).toBe(true);
  });

  it("公開するには、選択肢 4 つ・正解・解説が要る", () => {
    const result = validateQuizForm(
      { ...filled, choices: ["A", "", "C", "D"], correctIndex: null, explanation: " " },
      "published",
    );
    expect(result).toEqual({
      ok: false,
      errors: {
        "choices.1": "公開するには 4 つとも入力してください",
        correctIndex: "公開するには正解を選んでください",
        explanation: "公開するには解説を入力してください",
      },
    });
  });

  it("カテゴリと難易度が未選択なら、選ぶように伝える", () => {
    const result = validateQuizForm({ ...filled, categoryId: "", difficultyId: "" }, "draft");
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.categoryId).toBe("カテゴリを選んでください");
      expect(result.errors.difficultyId).toBe("難易度を選んでください");
    }
  });

  it("空白だけの問題文を拒む", () => {
    const result = validateQuizForm({ ...filled, question: "   " }, "draft");
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.question).toBe("問題文を入力してください");
  });

  it("正解にした欄が空なら、下書きでも拒む", () => {
    const result = validateQuizForm({ ...filled, choices: ["A", "", "C", "D"], correctIndex: 1 }, "draft");
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors["choices.1"]).toBe("正解にした選択肢を入力してください");
  });

  it("押したほうの状態で送る", () => {
    const result = validateQuizForm({ ...filled, status: "draft" }, "published");
    expect(result.ok && result.data.status).toBe("published");
  });
});

describe("toSaveQuizRequest", () => {
  it("空欄の選択肢は送らず、欄の選択肢の ID を添える", () => {
    const request = toSaveQuizRequest({
      ...filled,
      choices: [" A ", "", "C", ""],
      choiceIds: [CHOICE, null, null, null],
      correctIndex: 2,
    });
    expect(request.choices).toEqual([
      { id: CHOICE, body: "A", isCorrect: false },
      { id: undefined, body: "C", isCorrect: true },
    ]);
  });
});

describe("toQuizForm", () => {
  it("選択肢が 4 つ未満なら、残りの欄を空にする", () => {
    const form = toQuizForm({
      id: "q",
      categoryId: CATEGORY,
      difficultyId: DIFFICULTY,
      question: "問題",
      explanation: "解説 ![図](figure:abc)",
      status: "published",
      choices: [
        { id: CHOICE, body: "A", isCorrect: false },
        { id: "44444444-4444-4444-8444-444444444444", body: "B", isCorrect: true },
      ],
    });
    expect(form.choices).toEqual(["A", "B", "", ""]);
    expect(form.choiceIds).toEqual([CHOICE, "44444444-4444-4444-8444-444444444444", null, null]);
    expect(form.correctIndex).toBe(1);
    // 図の参照は文字のまま引き継ぐ
    expect(form.explanation).toBe("解説 ![図](figure:abc)");
  });
});
