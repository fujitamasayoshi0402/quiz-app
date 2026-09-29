"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { DeleteDialog } from "@/components/admin/delete-dialog";
import { FigureEditor } from "@/components/admin/figure-editor";
import { StatusBadge } from "@/components/admin/status-badge";
import { FigureImage } from "@/components/figure-image";
import { Markdown } from "@/components/markdown";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Field, FieldDescription, FieldError, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import {
  getFigureSource,
  useCreateQuiz,
  useDeleteQuiz,
  useGetFigureDetail,
  useGetQuiz,
  useUpdateQuiz,
} from "@/lib/api/generated/endpoints";
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
import { FIGURE_FILE_TYPES, uploadFigureFile } from "@/lib/figure-upload";
import {
  figureIdsIn,
  figurePreviewUrl,
  figureUrl,
  insertFigure,
  insertPdf,
  linkLabelOf,
  replaceFigure,
} from "@/lib/figures";

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
  const explanation = useWatch({ control: form.control, name: "explanation" });
  const [explanationTab, setExplanationTab] = useState("write");
  const figures = useExplanationFigures(slug, form.getValues, (value) =>
    form.setValue("explanation", value, { shouldDirty: true, shouldValidate: form.formState.isSubmitted }),
  );
  const { ref: registerExplanation, ...explanationField } = form.register("explanation");
  const fileInput = useRef<HTMLInputElement>(null);

  const create = useCreateQuiz({ mutation: { onSuccess: toList } });
  const update = useUpdateQuiz({ mutation: { onSuccess: toList } });
  const remove = useDeleteQuiz({ mutation: { onSuccess: toList } });
  const saving = create.isPending || update.isPending;
  const saveError = create.error ?? update.error ?? remove.error;

  const save = (status: QuizStatus) => {
    form.setValue("status", status);
    void form.handleSubmit(
      (values) => {
        const data = toSaveQuizRequest(values);
        if (quizId) update.mutate({ slug, id: quizId, data });
        else create.mutate({ slug, data });
      },
      // プレビューを開いたままだと、誤りのある入力欄が見えない
      (invalid) => {
        if (invalid.explanation) setExplanationTab("write");
      },
    )();
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
            <FieldDescription>
              Markdown で書けます（見出し、箇条書き、コード、リンク、表）。図は「図を描く」で描くか、「画像・PDF
              を入れる」で上げて入れます。PDF は 1 ページ目が画像として出て、押すと開きます。HTML
              と、ほかの場所の画像は表示されません。
            </FieldDescription>
            <Tabs value={explanationTab} onValueChange={setExplanationTab}>
              <div className="flex flex-wrap items-center gap-2">
                <TabsList>
                  <TabsTrigger value="write">書く</TabsTrigger>
                  <TabsTrigger value="preview">プレビュー</TabsTrigger>
                </TabsList>
                <div className="ml-auto flex gap-2">
                  <Button type="button" variant="outline" size="sm" onClick={figures.drawNew}>
                    図を描く
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={figures.uploading}
                    onClick={() => fileInput.current?.click()}
                  >
                    {figures.uploading ? "上げています…" : "画像・PDF を入れる"}
                  </Button>
                  <input
                    ref={fileInput}
                    type="file"
                    accept={FIGURE_FILE_TYPES.join(",")}
                    className="hidden"
                    onChange={(event) => {
                      const file = event.target.files?.[0];
                      // 同じファイルをもう一度選んでも、選び直しとして扱う
                      event.target.value = "";
                      if (file) void figures.upload(file);
                    }}
                  />
                </div>
              </div>
              {/* 入力欄は外さずに隠す。外すと、戻ったときにカーソルの位置や元に戻す履歴が消える */}
              <TabsContent value="write" forceMount className="space-y-3 data-[state=inactive]:hidden">
                <Textarea
                  id="explanation"
                  rows={8}
                  className="font-mono"
                  aria-invalid={!!errors.explanation}
                  {...explanationField}
                  ref={(element) => {
                    registerExplanation(element);
                    figures.bindTextarea(element);
                  }}
                />
                {figures.error !== null && <ApiErrorAlert error={figures.error} />}
                <ExplanationFigures
                  slug={slug}
                  ids={figureIdsIn(explanation)}
                  loading={figures.loading}
                  onRedraw={figures.redraw}
                />
              </TabsContent>
              <TabsContent value="preview" className="min-h-40 rounded-lg border px-3 py-2">
                {explanation.trim() ? (
                  <Markdown tenant={slug}>{explanation}</Markdown>
                ) : (
                  <p className="text-muted-foreground text-sm">解説がまだありません。</p>
                )}
              </TabsContent>
            </Tabs>
            <FieldError errors={[errors.explanation]} />
          </Field>
          <FigureEditor
            slug={slug}
            source={figures.drawing?.source}
            open={figures.drawing !== null}
            onOpenChange={(open) => !open && figures.close()}
            onSaved={figures.saved}
          />
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

/**
 * 解説に図を描いて入れる・描き直す（ADR-0020）。
 *
 * 新しい図はカーソルの位置に入れる。描き直した図は新しい ID になるので、本文の参照を差し替える。
 * 前の図は消さない。保存する前に編集をやめると、保存済みの解説は前の図を指したままになる。
 */
function useExplanationFigures(
  slug: string,
  getValues: (name: "explanation") => string,
  setExplanation: (value: string) => void,
) {
  const textarea = useRef<HTMLTextAreaElement | null>(null);
  const cursor = useRef<number | null>(null);
  const [drawing, setDrawing] = useState<{ source?: string; replaces?: string } | null>(null);
  const [loading, setLoading] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<unknown>(null);

  return {
    /** 入力欄を覚えておく。図を入れる位置（カーソル）を読むため */
    bindTextarea: (element: HTMLTextAreaElement | null) => {
      textarea.current = element;
    },
    drawing,
    loading,
    uploading,
    error,
    drawNew: () => {
      // ダイアログを開くと入力欄からフォーカスが外れる。入れる位置は、開く前に覚えておく
      cursor.current = textarea.current?.selectionStart ?? null;
      setError(null);
      setDrawing({});
    },
    redraw: async (id: string) => {
      setError(null);
      setLoading(id);
      try {
        const { source } = await getFigureSource(slug, id);
        setDrawing({ source, replaces: id });
      } catch (e) {
        setError(e);
      } finally {
        setLoading(null);
      }
    },
    saved: (id: string) => {
      const current = getValues("explanation");
      setExplanation(drawing?.replaces ? replaceFigure(current, drawing.replaces, id) : insertFigure(current, cursor.current, id));
    },
    close: () => setDrawing(null),
    /**
     * 画像か PDF を上げて、カーソルの位置に入れる。上げている間に入力を続けても、位置は選んだときのまま。
     * 画像は本文の中に出す。PDF は 1 ページ目の画像と、ファイル名を文字にした開くリンクにする。
     * どちらかは、API が中身で決めた種類に従う
     */
    upload: async (file: File) => {
      cursor.current = textarea.current?.selectionStart ?? null;
      setError(null);
      setUploading(true);
      try {
        const { id, kind } = await uploadFigureFile(slug, file);
        const current = getValues("explanation");
        setExplanation(
          kind === "pdf"
            ? insertPdf(current, cursor.current, id, linkLabelOf(file.name))
            : insertFigure(current, cursor.current, id, "画像"),
        );
      } catch (e) {
        setError(e);
      } finally {
        setUploading(false);
      }
    },
  };
}

/** 解説が指している図の一覧。描き直すときは、ここから選ぶ */
function ExplanationFigures({
  slug,
  ids,
  loading,
  onRedraw,
}: {
  slug: string;
  ids: string[];
  loading: string | null;
  onRedraw: (id: string) => void;
}) {
  if (ids.length === 0) return null;
  return (
    <div className="space-y-2">
      <p className="text-sm font-medium">解説の図</p>
      <ul className="flex flex-wrap gap-3">
        {ids.map((id, index) => (
          <ExplanationFigure
            key={id}
            slug={slug}
            id={id}
            label={`${index + 1} つ目の図`}
            loading={loading}
            onRedraw={onRedraw}
          />
        ))}
      </ul>
    </div>
  );
}

/**
 * 描き直せるのは draw.io の図だけ。画像と PDF には原本がない。
 * PDF は 1 ページ目の画像を出し、中身を確かめられるよう、開くリンクを添える（ADR-0021）
 */
function ExplanationFigure({
  slug,
  id,
  label,
  loading,
  onRedraw,
}: {
  slug: string;
  id: string;
  label: string;
  loading: string | null;
  onRedraw: (id: string) => void;
}) {
  const detail = useGetFigureDetail(slug, id, { query: { staleTime: Infinity, retry: false } });
  return (
    <li className="w-32 space-y-1">
      <FigureImage src={figurePreviewUrl(slug, id)} alt={label} className="h-24 w-full object-contain" />
      {detail.data?.kind === "pdf" && (
        <a
          href={figureUrl(slug, id)}
          target="_blank"
          rel="noopener noreferrer"
          className="text-primary block text-center text-xs underline underline-offset-4"
        >
          PDF を開く
        </a>
      )}
      {detail.data?.kind === "drawio" && (
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="w-full"
          disabled={loading !== null}
          onClick={() => onRedraw(id)}
        >
          {loading === id ? "読み込んでいます…" : "描き直す"}
        </Button>
      )}
      {detail.data?.kind === "image" && <p className="text-muted-foreground text-center text-xs">画像</p>}
    </li>
  );
}
