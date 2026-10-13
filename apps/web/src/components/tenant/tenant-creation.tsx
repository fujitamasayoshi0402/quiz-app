"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm, useWatch } from "react-hook-form";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { SignOutButton } from "@/components/tenant/sign-out-button";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  getGetTenantCreationQueryKey,
  getListMyTenantsQueryKey,
  useCreateTenant,
  useGetTenantCreation,
} from "@/lib/api/generated/endpoints";
import {
  CREATION_BLOCKS,
  type TenantFormInput,
  TenantFormSchema,
  type TenantFormValues,
} from "@/lib/tenant/tenant-form";

/**
 * 自分のテナントを作る（要件定義 U12、ADR-0028）。作ったら、その管理画面のカテゴリへ移る。中身は空で、まずカテゴリを作るため。
 *
 * **作れないときは、入力させる前に理由を示す。** 押してから失敗させない
 */
export function TenantCreation() {
  const creation = useGetTenantCreation();

  return (
    <>
      <div className="space-y-1 text-center">
        <h1 className="text-2xl font-semibold">テナントを作る</h1>
        <p className="text-sm text-muted-foreground">自分のテナントを作り、管理者としてクイズを作れます</p>
      </div>
      {creation.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : creation.isError ? (
        <ApiErrorAlert error={creation.error} />
      ) : creation.data.allowed ? (
        <TenantForm />
      ) : (
        <CreationBlocked reason={creation.data.reason} />
      )}
      <Link href="/" className="text-center text-sm text-muted-foreground underline underline-offset-4">
        所属しているテナントへ戻る
      </Link>
    </>
  );
}

function CreationBlocked({ reason }: { reason?: string | null }) {
  const block = CREATION_BLOCKS[reason ?? ""] ?? { title: "テナントを作れません", description: "" };
  return (
    <Card>
      <CardHeader>
        <CardTitle>{block.title}</CardTitle>
        <CardDescription>{block.description}</CardDescription>
      </CardHeader>
      {reason === "shared_account" && (
        <CardContent>
          <SignOutButton />
        </CardContent>
      )}
    </Card>
  );
}

function TenantForm() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const form = useForm<TenantFormInput, unknown, TenantFormValues>({
    resolver: zodResolver(TenantFormSchema),
    defaultValues: { name: "", slug: "" },
  });
  const { errors } = form.formState;
  const slug = useWatch({ control: form.control, name: "slug" });
  const create = useCreateTenant();

  const submit = form.handleSubmit(async (values) => {
    // 失敗は create.error として画面に出る
    const created = await create.mutateAsync({ data: values }).catch(() => null);
    if (!created) return;
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: getListMyTenantsQueryKey() }),
      queryClient.invalidateQueries({ queryKey: getGetTenantCreationQueryKey() }),
    ]);
    router.push(`/t/${created.slug}/admin/categories`);
  });

  return (
    <Card>
      <CardHeader>
        <CardDescription>
          作ったテナントは非公開で、あなたが招待した人だけが参加できます。テナントは 1 人 1 つ、クイズは 100 問、図は 50
          個まで作れます。
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="space-y-4">
          <Field data-invalid={!!errors.name}>
            <FieldLabel htmlFor="tenant-name">テナントの名前</FieldLabel>
            <Input id="tenant-name" autoComplete="off" aria-invalid={!!errors.name} {...form.register("name")} />
            <FieldError errors={[errors.name]} />
          </Field>
          <Field data-invalid={!!errors.slug}>
            <FieldLabel htmlFor="tenant-slug">URL に使う名前</FieldLabel>
            <Input
              id="tenant-slug"
              autoComplete="off"
              autoCapitalize="none"
              spellCheck={false}
              placeholder="my-quiz"
              aria-invalid={!!errors.slug}
              {...form.register("slug")}
            />
            <FieldDescription className="break-all">/t/{slug.trim() || "my-quiz"}</FieldDescription>
            <FieldError errors={[errors.slug]} />
          </Field>
          {create.isError && <ApiErrorAlert error={create.error} />}
          <Button type="submit" disabled={form.formState.isSubmitting}>
            {form.formState.isSubmitting ? "作っています…" : "作る"}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
