"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { DeleteDialog, ImpactSummary } from "@/components/admin/delete-dialog";
import { DifficultySection } from "@/components/admin/difficulty-section";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  useCategoryDeletionImpact,
  useDeleteCategory,
  useGetCategory,
  useUpdateCategory,
} from "@/lib/api/generated/endpoints";
import type { CategoryResponse } from "@/lib/api/generated/model";
import {
  type CategoryFormInput,
  CategoryFormSchema,
  type CategoryFormValues,
  blankToNull,
} from "@/lib/admin/category-form";
import { useInvalidateTenant } from "@/lib/admin/invalidate";

/** カテゴリ編集。カテゴリ情報と、**配下の難易度の管理**をこの画面で行う（要件定義「難易度の URL」）。 */
export function CategoryEditor({ slug, categoryId }: { slug: string; categoryId: string }) {
  const category = useGetCategory(slug, categoryId);
  if (category.isPending) return <Skeleton className="h-64 w-full" />;
  if (category.isError) return <ApiErrorAlert error={category.error} />;

  return (
    <div className="space-y-6">
      <CategoryForm slug={slug} category={category.data} />
      <DifficultySection slug={slug} categoryId={categoryId} />
    </div>
  );
}

function CategoryForm({ slug, category }: { slug: string; category: CategoryResponse }) {
  const router = useRouter();
  const invalidate = useInvalidateTenant(slug);

  const form = useForm<CategoryFormInput, unknown, CategoryFormValues>({
    resolver: zodResolver(CategoryFormSchema),
    defaultValues: { name: category.name, description: category.description ?? "", sortOrder: category.sortOrder },
  });
  const { errors, isDirty } = form.formState;

  const update = useUpdateCategory({
    mutation: {
      onSuccess: async (saved) => {
        await invalidate();
        form.reset({ name: saved.name, description: saved.description ?? "", sortOrder: saved.sortOrder });
      },
    },
  });
  const remove = useDeleteCategory({
    mutation: {
      onSuccess: async () => {
        await invalidate();
        router.push(`/t/${slug}/admin/categories`);
      },
    },
  });

  const submit = form.handleSubmit((values) =>
    update.mutate({
      slug,
      id: category.id,
      data: { name: values.name, description: blankToNull(values.description), sortOrder: values.sortOrder },
    }),
  );

  return (
    <Card>
      <CardHeader>
        <CardTitle>カテゴリ</CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="grid gap-4 sm:grid-cols-[1fr_1fr_6rem]">
          <Field data-invalid={!!errors.name}>
            <FieldLabel htmlFor="name">カテゴリ名</FieldLabel>
            <Input id="name" aria-invalid={!!errors.name} {...form.register("name")} />
            <FieldError errors={[errors.name]} />
          </Field>
          <Field data-invalid={!!errors.description}>
            <FieldLabel htmlFor="description">説明（任意）</FieldLabel>
            <Input id="description" {...form.register("description")} />
            <FieldError errors={[errors.description]} />
          </Field>
          <Field data-invalid={!!errors.sortOrder}>
            <FieldLabel htmlFor="sortOrder">並び順</FieldLabel>
            <Input id="sortOrder" type="number" {...form.register("sortOrder")} />
            <FieldError errors={[errors.sortOrder]} />
          </Field>
          <div className="space-y-2 sm:col-span-3">
            {(update.error ?? remove.error) && <ApiErrorAlert error={update.error ?? remove.error} />}
            <div className="flex items-center gap-2">
              <DeleteDialog
                name={category.name}
                pending={remove.isPending}
                impact={<CategoryImpact slug={slug} categoryId={category.id} />}
                onConfirm={() => remove.mutate({ slug, id: category.id })}
              />
              <Button type="submit" className="ml-auto" disabled={!isDirty || update.isPending}>
                {update.isSuccess && !isDirty ? "保存しました" : "保存する"}
              </Button>
            </div>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

function CategoryImpact({ slug, categoryId }: { slug: string; categoryId: string }) {
  const impact = useCategoryDeletionImpact(slug, categoryId);
  return (
    <ImpactSummary
      loading={impact.isPending}
      difficultyCount={impact.data?.difficultyCount ?? 0}
      quizCount={impact.data?.quizCount}
    />
  );
}
