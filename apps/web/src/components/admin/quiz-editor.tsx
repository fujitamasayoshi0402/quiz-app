"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { Controller, useForm, useWatch } from "react-hook-form";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { DeleteDialog } from "@/components/admin/delete-dialog";
import { StatusBadge } from "@/components/admin/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Field, FieldDescription, FieldError, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { useCreateQuiz, useDeleteQuiz, useGetQuiz, useUpdateQuiz } from "@/lib/api/generated/endpoints";
import { useCatalog } from "@/lib/admin/catalog";
import { useInvalidateTenant } from "@/lib/admin/invalidate";
import {
  CHOICE_COUNT,
  type QuizFormValues,
  QuizFormSchema,
  type QuizStatus,
  emptyQuizForm,
  toQuizForm,
  toSaveQuizRequest,
} from "@/lib/admin/quiz-form";

/** 既存のクイズを読み込んでから編集フォームを出す */
export function EditQuiz({ slug, quizId }: { slug: string; quizId: string }) {
  const quiz = useGetQuiz(slug, quizId);
  if (quiz.isPending) return <Skeleton className="h-96 w-full" />;
  if (quiz.isError) return <ApiErrorAlert error={quiz.error} />;
  return <QuizEditor slug={slug} quizId={quizId} initial={toQuizForm(quiz.data)} currentStatus={quiz.data.status} />;
}

export function NewQuiz({ slug }: { slug: string }) {
  return <QuizEditor slug={slug} initial={emptyQuizForm} />;
}

/**
 * クイズの作成・編集。
 *
 * **カテゴリを選ぶまで難易度を選べない。** カテゴリを変えたら難易度の選択を外す（要件定義「画面遷移」）。
 * 保存ボタンを「下書き」と「公開」に分け、押したほうの状態で検証する。公開の条件は下書きより厳しい。
 */
function QuizEditor({
  slug,
  quizId,
  initial,
  currentStatus,
}: {
  slug: string;
  quizId?: string;
  initial: QuizFormValues;
  currentStatus?: string;
}) {
  const router = useRouter();
  const catalog = useCatalog(slug);
  const invalidate = useInvalidateTenant(slug);
  const toList = async () => {
    await invalidate();
    router.push(`/t/${slug}/admin/quizzes`);
  };

  const form = useForm<QuizFormValues>({ resolver: zodResolver(QuizFormSchema), defaultValues: initial });
  const { errors } = form.formState;
  const categoryId = useWatch({ control: form.control, name: "categoryId" });

  const create = useCreateQuiz({ mutation: { onSuccess: toList } });
  const update = useUpdateQuiz({ mutation: { onSuccess: toList } });
  const remove = useDeleteQuiz({ mutation: { onSuccess: toList } });
  const saving = create.isPending || update.isPending;
  const saveError = create.error ?? update.error ?? remove.error;

  const save = (status: QuizStatus) => {
    form.setValue("status", status);
    void form.handleSubmit((values) => {
      const data = toSaveQuizRequest(values);
      if (quizId) update.mutate({ slug, id: quizId, data });
      else create.mutate({ slug, data });
    })();
  };

  return (
    <form onSubmit={(event) => event.preventDefault()} className="space-y-6">
      {currentStatus && (
        <div className="flex items-center gap-2 text-sm">
          現在の状態 <StatusBadge status={currentStatus} />
        </div>
      )}

      <Card>
        <CardContent className="grid gap-4 sm:grid-cols-2">
          <Field data-invalid={!!errors.categoryId}>
            <FieldLabel>カテゴリ</FieldLabel>
            <Controller
              control={form.control}
              name="categoryId"
              render={({ field }) => (
                <Select
                  value={field.value}
                  onValueChange={(value) => {
                    field.onChange(value);
                    form.setValue("difficultyId", "");
                  }}
                >
                  <SelectTrigger aria-invalid={!!errors.categoryId}>
                    <SelectValue placeholder="選んでください" />
                  </SelectTrigger>
                  <SelectContent>
                    {catalog.categories.data?.map((category) => (
                      <SelectItem key={category.id} value={category.id}>
                        {category.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            />
            <FieldError errors={[errors.categoryId]} />
          </Field>
          <Field data-invalid={!!errors.difficultyId}>
            <FieldLabel>難易度</FieldLabel>
            <Controller
              control={form.control}
              name="difficultyId"
              render={({ field }) => (
                <Select value={field.value} onValueChange={field.onChange} disabled={!categoryId}>
                  <SelectTrigger aria-invalid={!!errors.difficultyId}>
                    <SelectValue placeholder={categoryId ? "選んでください" : "先にカテゴリを選ぶ"} />
                  </SelectTrigger>
                  <SelectContent>
                    {catalog.difficultiesOf(categoryId).map((difficulty) => (
                      <SelectItem key={difficulty.id} value={difficulty.id}>
                        {difficulty.name}（レベル {difficulty.level}）
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            />
            <FieldError errors={[errors.difficultyId]} />
          </Field>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-6">
          <Field data-invalid={!!errors.question}>
            <FieldLabel htmlFor="question">問題文</FieldLabel>
            <Textarea id="question" rows={3} aria-invalid={!!errors.question} {...form.register("question")} />
            <FieldError errors={[errors.question]} />
          </Field>

          <FieldSet>
            <FieldLegend variant="label">選択肢</FieldLegend>
            <FieldDescription>正解の選択肢を 1 つ選んでください。</FieldDescription>
            <Controller
              control={form.control}
              name="correctIndex"
              render={({ field }) => (
                <RadioGroup
                  value={field.value === null ? "" : String(field.value)}
                  onValueChange={(value) => field.onChange(Number(value))}
                  className="gap-3"
                >
                  {Array.from({ length: CHOICE_COUNT }, (_, index) => {
                    const error = errors.choices?.[index];
                    return (
                      <Field key={index} data-invalid={!!error}>
                        <div className="flex items-center gap-3">
                          <RadioGroupItem value={String(index)} id={`correct-${index}`} aria-label={`選択肢 ${index + 1} を正解にする`} />
                          <Input
                            placeholder={`選択肢 ${index + 1}`}
                            aria-invalid={!!error}
                            {...form.register(`choices.${index}`)}
                          />
                        </div>
                        <FieldError errors={[error]} className="pl-7" />
                      </Field>
                    );
                  })}
                </RadioGroup>
              )}
            />
            <FieldError errors={[errors.correctIndex]} />
          </FieldSet>

          <Field data-invalid={!!errors.explanation}>
            <FieldLabel htmlFor="explanation">解説</FieldLabel>
            <Textarea id="explanation" rows={5} aria-invalid={!!errors.explanation} {...form.register("explanation")} />
            <FieldError errors={[errors.explanation]} />
          </Field>
        </CardContent>
      </Card>

      {saveError && <ApiErrorAlert error={saveError} />}

      <div className="flex flex-wrap items-center gap-2">
        {quizId && (
          <DeleteDialog
            name={initial.question.slice(0, 30)}
            pending={remove.isPending}
            onConfirm={() => remove.mutate({ slug, id: quizId })}
          />
        )}
        <div className="ml-auto flex gap-2">
          <Button type="button" variant="outline" disabled={saving} onClick={() => save("draft")}>
            下書きとして保存
          </Button>
          <Button type="button" disabled={saving} onClick={() => save("published")}>
            {currentStatus === "published" ? "公開したまま保存" : "公開する"}
          </Button>
        </div>
      </div>
    </form>
  );
}
