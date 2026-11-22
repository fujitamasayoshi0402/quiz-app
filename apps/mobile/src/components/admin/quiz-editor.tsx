import { useRouter } from "expo-router";
import { useState } from "react";
import { Linking, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { Markdown } from "@/components/markdown";
import { OptionGroup } from "@/components/play";
import { Button, ErrorMessage, Loading, colors, styles } from "@/components/ui";
import { useCatalog, useInvalidateTenant } from "@/lib/admin/catalog";
import {
  MAX_CHOICE_COUNT,
  MIN_CHOICE_COUNT,
  type QuizFormErrors,
  type QuizFormValues,
  type QuizStatus,
  emptyQuizForm,
  toQuizForm,
  validateQuizForm,
} from "@/lib/admin/quiz-form";
import { statusLabel } from "@/lib/admin/quiz-list";
import { useCreateQuiz, useGetQuiz, useUpdateQuiz } from "@/lib/api/generated/endpoints";

/** 既存のクイズを読み込んでから編集フォームを出す */
export function EditQuiz({ slug, quizId }: { slug: string; quizId: string }) {
  const quiz = useGetQuiz(slug, quizId);
  if (quiz.isPending) return <Loading />;
  if (quiz.isError) {
    return (
      <View style={styles.screen}>
        <ErrorMessage error={quiz.error} />
        <Button label="もう一度読み込む" onPress={() => void quiz.refetch()} />
      </View>
    );
  }
  return (
    <QuizEditor
      slug={slug}
      quizId={quizId}
      initial={toQuizForm(quiz.data)}
      currentStatus={quiz.data.status}
      sourceUrl={quiz.data.sourceUrl}
    />
  );
}

export function NewQuiz({ slug }: { slug: string }) {
  return <QuizEditor slug={slug} initial={emptyQuizForm} />;
}

/**
 * クイズの作成・編集（DEV-227、ADR-0037。web の `quiz-editor.tsx` と同じ規則）。
 *
 * カテゴリを選ぶまで難易度を選べない。カテゴリを変えたら難易度の選択を外す。
 * 保存ボタンを「下書き」と「公開」に分け、押したほうの状態で検証する。
 * 図は描かず、選ばない（web で行う）。解説の `figure:` は文字のまま編集でき、保存しても壊さない
 */
function QuizEditor({
  slug,
  quizId,
  initial,
  currentStatus,
  sourceUrl,
}: {
  slug: string;
  quizId?: string;
  initial: QuizFormValues;
  currentStatus?: string;
  sourceUrl?: string | null;
}) {
  const router = useRouter();
  const catalog = useCatalog(slug);
  const invalidate = useInvalidateTenant(slug);
  const [form, setForm] = useState(initial);
  const [errors, setErrors] = useState<QuizFormErrors>({});
  const [questionPreview, setQuestionPreview] = useState(false);
  const [preview, setPreview] = useState(false);

  const finish = async () => {
    await invalidate();
    router.back();
  };
  const create = useCreateQuiz({ mutation: { onSuccess: finish } });
  const update = useUpdateQuiz({ mutation: { onSuccess: finish } });
  const saving = create.isPending || update.isPending;
  const saveError = create.error ?? update.error;

  const set = <K extends keyof QuizFormValues>(key: K, value: QuizFormValues[K]) =>
    setForm((current) => ({ ...current, [key]: value }));

  const toggleCorrect = (index: number) =>
    setForm((current) => ({
      ...current,
      correctIndexes: current.correctIndexes.includes(index)
        ? current.correctIndexes.filter((i) => i !== index)
        : [...current.correctIndexes, index].sort((a, b) => a - b),
    }));

  const addChoice = () =>
    setForm((current) => {
      if (current.choices.length >= MAX_CHOICE_COUNT) return current;
      return {
        ...current,
        choices: [...current.choices, ""],
        choiceIds: [...current.choiceIds, null],
      };
    });

  const removeChoice = (index: number) =>
    setForm((current) => {
      if (current.choices.length <= MIN_CHOICE_COUNT) return current;
      return {
        ...current,
        choices: current.choices.filter((_, i) => i !== index),
        choiceIds: current.choiceIds.filter((_, i) => i !== index),
        correctIndexes: current.correctIndexes.filter((i) => i !== index).map((i) => (i > index ? i - 1 : i)),
      };
    });

  const save = (status: QuizStatus) => {
    const result = validateQuizForm(form, status);
    if (!result.ok) {
      setErrors(result.errors);
      // プレビューを開いたままだと、誤りのある入力欄が見えない
      if (result.errors.question) setQuestionPreview(false);
      if (result.errors.explanation) setPreview(false);
      return;
    }
    setErrors({});
    if (quizId) update.mutate({ slug, id: quizId, data: result.data });
    else create.mutate({ slug, data: result.data });
  };

  const categories = (catalog.categories.data ?? []).map((c) => ({ value: c.id, label: c.name }));
  const difficulties = catalog
    .difficultiesOf(form.categoryId)
    .map((d) => ({ value: d.id, label: d.name, hint: `レベル ${d.level}` }));

  return (
    <ScrollView
      style={styles.scroll}
      contentContainerStyle={styles.scrollContent}
      keyboardShouldPersistTaps="handled"
      automaticallyAdjustKeyboardInsets
    >
      {currentStatus && <Text style={styles.muted}>現在の状態: {statusLabel(currentStatus)}</Text>}
      {sourceUrl && (
        <View style={styles.notice}>
          <Text style={[styles.text, { fontWeight: "600" }]}>AI が技術ニュースから作った下書きです</Text>
          <Text style={styles.muted}>元の発表と照らし合わせて、確かめてから公開してください。</Text>
          <Pressable accessibilityRole="link" onPress={() => void Linking.openURL(sourceUrl)}>
            <Text style={[styles.text, { color: colors.primary }]} numberOfLines={1}>
              {sourceUrl}
            </Text>
          </Pressable>
        </View>
      )}

      <Field label="カテゴリ" error={errors.categoryId}>
        {catalog.categories.isPending ? (
          <Text style={styles.muted}>読み込んでいます…</Text>
        ) : catalog.categories.isError ? (
          <ErrorMessage error={catalog.categories.error} />
        ) : categories.length === 0 ? (
          <Text style={styles.muted}>カテゴリがありません。web の管理画面で作ってください。</Text>
        ) : (
          <OptionGroup
            value={form.categoryId}
            onChange={(categoryId) => setForm((current) => ({ ...current, categoryId, difficultyId: "" }))}
            options={categories}
          />
        )}
      </Field>

      <Field label="難易度" error={errors.difficultyId}>
        {!form.categoryId ? (
          <Text style={styles.muted}>先にカテゴリを選んでください。</Text>
        ) : (
          <OptionGroup
            value={form.difficultyId}
            onChange={(difficultyId) => set("difficultyId", difficultyId)}
            options={difficulties}
          />
        )}
      </Field>

      <Field
        label="問題文"
        hint="Markdown で書けます。図は web の管理画面で入れます。`figure:` の行は、消さなければそのまま残ります。"
        error={errors.question}
      >
        <View style={s.tabs}>
          <Button
            label="書く"
            variant={questionPreview ? "secondary" : "primary"}
            onPress={() => setQuestionPreview(false)}
          />
          <Button
            label="プレビュー"
            variant={questionPreview ? "primary" : "secondary"}
            onPress={() => setQuestionPreview(true)}
          />
        </View>
        {questionPreview ? (
          <View style={styles.panel}>
            {form.question.trim() ? (
              // tenant を渡さないので、図は出ない
              <Markdown>{form.question}</Markdown>
            ) : (
              <Text style={styles.muted}>問題文はまだありません。</Text>
            )}
            <Text style={styles.muted}>図はプレビューに出ません。</Text>
          </View>
        ) : (
          <TextInput
            style={[s.input, s.multiline, errors.question && s.invalid]}
            multiline
            value={form.question}
            onChangeText={(question) => set("question", question)}
            accessibilityLabel="問題文"
          />
        )}
      </Field>

      <Field
        label="選択肢"
        hint={`選択肢は ${MIN_CHOICE_COUNT}〜${MAX_CHOICE_COUNT} 個まで設定できます。正解の選択肢を 1 つ以上選んでください。`}
        error={errors.correctIndexes}
      >
        <View style={{ gap: 12 }}>
          {form.choices.map((choiceBody, index) => {
            const checked = form.correctIndexes.includes(index);
            const error = errors[`choices.${index}`];
            return (
              <View key={index} style={{ gap: 4 }}>
                <View style={s.choiceRow}>
                  <Pressable
                    accessibilityRole="checkbox"
                    accessibilityState={{ checked }}
                    accessibilityLabel={`選択肢 ${index + 1} を正解にする`}
                    hitSlop={8}
                    onPress={() => toggleCorrect(index)}
                    style={[s.checkbox, checked && s.checkboxChecked]}
                  >
                    {checked && <Text style={s.checkboxMark}>✓</Text>}
                  </Pressable>
                  <TextInput
                    style={[s.input, s.choiceInput, error && s.invalid]}
                    placeholder={`選択肢 ${index + 1}`}
                    value={choiceBody}
                    onChangeText={(body) =>
                      set(
                        "choices",
                        form.choices.map((current, i) => (i === index ? body : current)),
                      )
                    }
                  />
                  {form.choices.length > MIN_CHOICE_COUNT && (
                    <Pressable
                      accessibilityRole="button"
                      accessibilityLabel={`選択肢 ${index + 1} を消す`}
                      onPress={() => removeChoice(index)}
                    >
                      <Text style={styles.muted}>消す</Text>
                    </Pressable>
                  )}
                </View>
                {error && <Text style={[styles.error, { paddingLeft: 32 }]}>{error}</Text>}
              </View>
            );
          })}
          {form.choices.length < MAX_CHOICE_COUNT && (
            <Button label="選択肢を足す" variant="secondary" onPress={addChoice} />
          )}
        </View>
      </Field>

      <Field
        label="解説"
        hint="Markdown で書けます。図は web の管理画面で入れます。`figure:` の行は、消さなければそのまま残ります。"
        error={errors.explanation}
      >
        <View style={s.tabs}>
          <Button label="書く" variant={preview ? "secondary" : "primary"} onPress={() => setPreview(false)} />
          <Button label="プレビュー" variant={preview ? "primary" : "secondary"} onPress={() => setPreview(true)} />
        </View>
        {preview ? (
          <View style={styles.panel}>
            {form.explanation.trim() ? (
              // tenant を渡さないので、図は出ない
              <Markdown>{form.explanation}</Markdown>
            ) : (
              <Text style={styles.muted}>解説はまだありません。</Text>
            )}
            <Text style={styles.muted}>図はプレビューに出ません。</Text>
          </View>
        ) : (
          <TextInput
            style={[s.input, s.multiline, s.code, errors.explanation && s.invalid]}
            multiline
            autoCapitalize="none"
            autoCorrect={false}
            value={form.explanation}
            onChangeText={(explanation) => set("explanation", explanation)}
            accessibilityLabel="解説"
          />
        )}
      </Field>

      {Object.keys(errors).length > 0 && <Text style={styles.error}>入力を確かめてください。</Text>}
      {saveError && <ErrorMessage error={saveError} />}
      <View style={{ gap: 12 }}>
        <Button label={saving ? "保存しています…" : "公開する"} onPress={() => save("published")} disabled={saving} />
        <Button label="下書きとして保存" variant="secondary" onPress={() => save("draft")} disabled={saving} />
      </View>
    </ScrollView>
  );
}

function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <View style={{ gap: 8 }}>
      <Text style={[styles.text, { fontWeight: "600" }]}>{label}</Text>
      {hint && <Text style={styles.muted}>{hint}</Text>}
      {children}
      {error && <Text style={styles.error}>{error}</Text>}
    </View>
  );
}

const s = StyleSheet.create({
  input: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 15,
    color: colors.text,
    backgroundColor: colors.background,
  },
  multiline: { minHeight: 96, textAlignVertical: "top" },
  code: { minHeight: 200, fontFamily: Platform.select({ ios: "Menlo", default: "monospace" }) },
  invalid: { borderColor: colors.danger },
  choiceRow: { flexDirection: "row", alignItems: "center", gap: 12 },
  choiceInput: { flex: 1 },
  checkbox: {
    width: 20,
    height: 20,
    borderRadius: 5,
    borderWidth: 1.5,
    borderColor: colors.muted,
    alignItems: "center",
    justifyContent: "center",
  },
  checkboxChecked: { borderColor: colors.text, backgroundColor: colors.text },
  checkboxMark: { color: colors.background, fontSize: 13, fontWeight: "700" },
  tabs: { flexDirection: "row", gap: 8 },
});
