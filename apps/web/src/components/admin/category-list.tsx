"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useCreateCategory, useCreateDifficulty, useListCategories } from "@/lib/api/generated/endpoints";
import {
  type NewCategoryFormInput,
  NewCategoryFormSchema,
  type NewCategoryFormValues,
  blankToNull,
} from "@/lib/admin/category-form";
import { useInvalidateTenant } from "@/lib/admin/invalidate";

export function CategoryList({ slug }: { slug: string }) {
  const categories = useListCategories(slug);

  return (
    <div className="space-y-6">
      {categories.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : categories.isError ? (
        <ApiErrorAlert error={categories.error} />
      ) : categories.data.length === 0 ? (
        <p className="text-muted-foreground text-sm">カテゴリがありません。まず 1 つ作ってください。</p>
      ) : (
        <div className="bg-background rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>カテゴリ</TableHead>
                <TableHead>説明</TableHead>
                <TableHead className="w-16 text-right">並び順</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {categories.data.map((category) => (
                <TableRow key={category.id}>
                  <TableCell>
                    <Link
                      href={`/t/${slug}/admin/categories/${category.id}`}
                      className="font-medium underline-offset-4 hover:underline"
                    >
                      {category.name}
                    </Link>
                  </TableCell>
                  <TableCell className="text-muted-foreground max-w-0 truncate">{category.description}</TableCell>
                  <TableCell className="text-right">{category.sortOrder}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <NewCategoryForm slug={slug} />
    </div>
  );
}

/**
 * カテゴリの作成。最初の難易度も同じ画面で入力させる。
 *
 * API はカテゴリと難易度で分かれているため 2 回呼ぶ。**2 回目が失敗してもカテゴリは残る**が、
 * 編集画面で難易度を足せば回復できるので、そのまま編集画面へ進める。
 */
function NewCategoryForm({ slug }: { slug: string }) {
  const router = useRouter();
  const invalidate = useInvalidateTenant(slug);

  const form = useForm<NewCategoryFormInput, unknown, NewCategoryFormValues>({
    resolver: zodResolver(NewCategoryFormSchema),
    defaultValues: { name: "", description: "", difficultyName: "", difficultyLevel: 1 },
  });
  const { errors } = form.formState;

  const createCategory = useCreateCategory();
  const createDifficulty = useCreateDifficulty();

  const submit = form.handleSubmit(async (values) => {
    // 失敗は createCategory.error として画面に出る。ここでは先へ進まないだけ
    const category = await createCategory
      .mutateAsync({ slug, data: { name: values.name, description: blankToNull(values.description) } })
      .catch(() => null);
    if (!category) return;

    // 失敗しても進める。編集画面は難易度が 0 件だと警告を出し、そこで足せる
    await createDifficulty
      .mutateAsync({
        slug,
        categoryId: category.id,
        data: { name: values.difficultyName, level: values.difficultyLevel },
      })
      .catch(() => null);
    await invalidate();
    router.push(`/t/${slug}/admin/categories/${category.id}`);
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>カテゴリを作る</CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
          <Field data-invalid={!!errors.name}>
            <FieldLabel htmlFor="category-name">カテゴリ名</FieldLabel>
            <Input id="category-name" aria-invalid={!!errors.name} {...form.register("name")} />
            <FieldError errors={[errors.name]} />
          </Field>
          <Field data-invalid={!!errors.description}>
            <FieldLabel htmlFor="category-description">説明（任意）</FieldLabel>
            <Input id="category-description" {...form.register("description")} />
            <FieldError errors={[errors.description]} />
          </Field>
          <Field data-invalid={!!errors.difficultyName}>
            <FieldLabel htmlFor="difficulty-name">最初の難易度</FieldLabel>
            <Input
              id="difficulty-name"
              placeholder="例: 基礎"
              aria-invalid={!!errors.difficultyName}
              {...form.register("difficultyName")}
            />
            <FieldError errors={[errors.difficultyName]} />
          </Field>
          <Field data-invalid={!!errors.difficultyLevel}>
            <FieldLabel htmlFor="difficulty-level">レベル</FieldLabel>
            <Input
              id="difficulty-level"
              type="number"
              min={1}
              aria-invalid={!!errors.difficultyLevel}
              {...form.register("difficultyLevel")}
            />
            <FieldError errors={[errors.difficultyLevel]} />
          </Field>
          <div className="space-y-2 sm:col-span-2">
            {createCategory.isError && <ApiErrorAlert error={createCategory.error} />}
            <Button type="submit" disabled={form.formState.isSubmitting}>
              作成する
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}
