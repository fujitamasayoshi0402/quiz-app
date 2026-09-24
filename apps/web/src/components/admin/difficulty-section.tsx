"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { TriangleAlert } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { DeleteDialog, ImpactSummary } from "@/components/admin/delete-dialog";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  useCreateDifficulty,
  useDeleteDifficulty,
  useDifficultyDeletionImpact,
  useListDifficulties,
  useUpdateDifficulty,
} from "@/lib/api/generated/endpoints";
import type { DifficultyResponse } from "@/lib/api/generated/model";
import {
  type DifficultyFormInput,
  DifficultyFormSchema,
  type DifficultyFormValues,
  blankToNull,
} from "@/lib/admin/category-form";
import { useInvalidateTenant } from "@/lib/admin/invalidate";

/**
 * カテゴリ配下の難易度。
 *
 * レベルは難しさの大小を表す尺度で、一意ではない。同じレベルに複数の難易度を並べてよい
 * （AWS のアソシエイト級に SAA / DVA が並ぶ、など）。同じレベル内の順は並び順で決める。
 */
export function DifficultySection({ slug, categoryId }: { slug: string; categoryId: string }) {
  const difficulties = useListDifficulties(slug, categoryId);
  const [editingId, setEditingId] = useState<string | null>(null);

  return (
    <Card>
      <CardHeader>
        <CardTitle>難易度</CardTitle>
        <CardDescription>レベルは難しさの目安です。同じレベルの難易度をいくつ並べてもかまいません。</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {difficulties.isPending ? (
          <Skeleton className="h-24 w-full" />
        ) : difficulties.isError ? (
          <ApiErrorAlert error={difficulties.error} />
        ) : difficulties.data.length === 0 ? (
          <Alert>
            <TriangleAlert />
            <AlertDescription>難易度がないと、このカテゴリにクイズを作れません。1 つ以上追加してください。</AlertDescription>
          </Alert>
        ) : (
          <ul className="divide-y rounded-lg border">
            {difficulties.data.map((difficulty) =>
              editingId === difficulty.id ? (
                <li key={difficulty.id} className="p-3">
                  <DifficultyForm
                    slug={slug}
                    categoryId={categoryId}
                    difficulty={difficulty}
                    onDone={() => setEditingId(null)}
                  />
                </li>
              ) : (
                <DifficultyRow
                  key={difficulty.id}
                  slug={slug}
                  categoryId={categoryId}
                  difficulty={difficulty}
                  onEdit={() => setEditingId(difficulty.id)}
                />
              ),
            )}
          </ul>
        )}
        <div className="space-y-2">
          <p className="text-sm font-medium">難易度を追加する</p>
          <DifficultyForm slug={slug} categoryId={categoryId} />
        </div>
      </CardContent>
    </Card>
  );
}

function DifficultyRow({
  slug,
  categoryId,
  difficulty,
  onEdit,
}: {
  slug: string;
  categoryId: string;
  difficulty: DifficultyResponse;
  onEdit: () => void;
}) {
  const invalidate = useInvalidateTenant(slug);
  const remove = useDeleteDifficulty({ mutation: { onSuccess: () => invalidate() } });

  return (
    <li className="space-y-2 p-3">
      <div className="flex items-center gap-3">
        <div className="flex-1">
          <p className="font-medium">{difficulty.name}</p>
          <p className="text-muted-foreground text-xs">
            レベル {difficulty.level}・並び順 {difficulty.sortOrder}
            {difficulty.description && `・${difficulty.description}`}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={onEdit}>
          編集
        </Button>
        <DeleteDialog
          name={difficulty.name}
          pending={remove.isPending}
          impact={<DifficultyImpact slug={slug} categoryId={categoryId} id={difficulty.id} />}
          onConfirm={() => remove.mutate({ slug, categoryId, id: difficulty.id })}
        />
      </div>
      {remove.isError && <ApiErrorAlert error={remove.error} />}
    </li>
  );
}

function DifficultyImpact({ slug, categoryId, id }: { slug: string; categoryId: string; id: string }) {
  const impact = useDifficultyDeletionImpact(slug, categoryId, id);
  return <ImpactSummary loading={impact.isPending} quizCount={impact.data?.quizCount} />;
}

/** `difficulty` を渡すと編集、渡さないと追加。 */
function DifficultyForm({
  slug,
  categoryId,
  difficulty,
  onDone,
}: {
  slug: string;
  categoryId: string;
  difficulty?: DifficultyResponse;
  onDone?: () => void;
}) {
  const invalidate = useInvalidateTenant(slug);
  const empty: DifficultyFormInput = { name: "", level: 1, sortOrder: 0, description: "" };
  const form = useForm<DifficultyFormInput, unknown, DifficultyFormValues>({
    resolver: zodResolver(DifficultyFormSchema),
    defaultValues: difficulty
      ? {
          name: difficulty.name,
          level: difficulty.level,
          sortOrder: difficulty.sortOrder,
          description: difficulty.description ?? "",
        }
      : empty,
  });
  const { errors } = form.formState;

  const onSuccess = async () => {
    await invalidate();
    if (difficulty) onDone?.();
    else form.reset(empty);
  };
  const create = useCreateDifficulty({ mutation: { onSuccess } });
  const update = useUpdateDifficulty({ mutation: { onSuccess } });

  const submit = form.handleSubmit((values) => {
    const data = { ...values, description: blankToNull(values.description) };
    if (difficulty) update.mutate({ slug, categoryId, id: difficulty.id, data });
    else create.mutate({ slug, categoryId, data });
  });

  const prefix = difficulty ? `difficulty-${difficulty.id}` : "difficulty-new";
  return (
    <form onSubmit={submit} className="space-y-2">
      <div className="grid gap-3 sm:grid-cols-[1fr_5rem_5rem_1fr]">
        <Field data-invalid={!!errors.name}>
          <FieldLabel htmlFor={`${prefix}-name`}>名前</FieldLabel>
          <Input id={`${prefix}-name`} aria-invalid={!!errors.name} {...form.register("name")} />
          <FieldError errors={[errors.name]} />
        </Field>
        <Field data-invalid={!!errors.level}>
          <FieldLabel htmlFor={`${prefix}-level`}>レベル</FieldLabel>
          <Input id={`${prefix}-level`} type="number" min={1} aria-invalid={!!errors.level} {...form.register("level")} />
          <FieldError errors={[errors.level]} />
        </Field>
        <Field data-invalid={!!errors.sortOrder}>
          <FieldLabel htmlFor={`${prefix}-sort`}>並び順</FieldLabel>
          <Input id={`${prefix}-sort`} type="number" {...form.register("sortOrder")} />
          <FieldError errors={[errors.sortOrder]} />
        </Field>
        <Field data-invalid={!!errors.description}>
          <FieldLabel htmlFor={`${prefix}-description`}>説明（任意）</FieldLabel>
          <Input id={`${prefix}-description`} {...form.register("description")} />
          <FieldError errors={[errors.description]} />
        </Field>
      </div>
      {(create.error ?? update.error) && <ApiErrorAlert error={create.error ?? update.error} />}
      <div className="flex justify-end gap-2">
        {difficulty && (
          <Button type="button" variant="outline" size="sm" onClick={onDone}>
            やめる
          </Button>
        )}
        <Button type="submit" size="sm" disabled={create.isPending || update.isPending}>
          {difficulty ? "保存する" : "追加する"}
        </Button>
      </div>
    </form>
  );
}
