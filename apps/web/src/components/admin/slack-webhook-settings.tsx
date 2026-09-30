"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { ApiErrorAlert } from "@/components/api-error-alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { useConfigureSlackWebhook, useGetSlackWebhook, useRemoveSlackWebhook } from "@/lib/api/generated/endpoints";
import { useInvalidateTenant } from "@/lib/admin/invalidate";
import {
  type SlackWebhookFormInput,
  SlackWebhookFormSchema,
  type SlackWebhookFormValues,
} from "@/lib/admin/slack-webhook-form";

const dateFormat = new Intl.DateTimeFormat("ja-JP", { dateStyle: "medium", timeStyle: "short" });

/**
 * クイズの追加・更新を知らせる、Slack の通知先（ADR-0022）。
 *
 * **設定した URL は表示しない。** バックエンドは URL を返さず、設定したかどうかと日時だけを返す。
 * 変えたいときは、新しい URL で置き換える。
 */
export function SlackWebhookSettings({ slug }: { slug: string }) {
  const setting = useGetSlackWebhook(slug);

  if (setting.isPending) return <Skeleton className="h-64 w-full" />;
  if (setting.isError) return <ApiErrorAlert error={setting.error} />;

  const { configured, configuredAt } = setting.data;
  return (
    <Card>
      <CardHeader>
        <CardTitle>Slack への通知</CardTitle>
        <CardDescription>
          クイズを追加・更新・公開したときに、Slack のチャンネルへ知らせます。Slack で Incoming Webhook を作り、その URL
          を入れてください。
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          {configured ? (
            <>
              <Badge>設定済み</Badge>
              {configuredAt && (
                <span className="text-muted-foreground">{dateFormat.format(new Date(configuredAt))} に設定</span>
              )}
            </>
          ) : (
            <Badge variant="outline">未設定</Badge>
          )}
        </div>
        <WebhookForm slug={slug} configured={configured} />
        {configured && <RemoveWebhook slug={slug} />}
      </CardContent>
    </Card>
  );
}

function WebhookForm({ slug, configured }: { slug: string; configured: boolean }) {
  const invalidate = useInvalidateTenant(slug);
  const form = useForm<SlackWebhookFormInput, unknown, SlackWebhookFormValues>({
    resolver: zodResolver(SlackWebhookFormSchema),
    defaultValues: { url: "" },
  });
  const { errors } = form.formState;
  const configure = useConfigureSlackWebhook();

  const submit = form.handleSubmit(async (values) => {
    // 失敗は configure.error として画面に出る
    const saved = await configure.mutateAsync({ slug, data: values }).catch(() => null);
    if (!saved) return;
    // 入れた URL を欄に残さない。設定したあとは、画面のどこにも出さない
    form.reset();
    await invalidate();
  });

  return (
    <form onSubmit={submit} className="space-y-4">
      <Field data-invalid={!!errors.url}>
        <FieldLabel htmlFor="slack-webhook-url">{configured ? "新しい Webhook の URL" : "Webhook の URL"}</FieldLabel>
        <Input
          id="slack-webhook-url"
          inputMode="url"
          autoComplete="off"
          spellCheck={false}
          placeholder="https://hooks.slack.com/services/..."
          aria-invalid={!!errors.url}
          {...form.register("url")}
        />
        <FieldDescription>
          URL を知っている人は、誰でもそのチャンネルに投稿できます。保存した URL は、あとから表示できません。
        </FieldDescription>
        <FieldError errors={[errors.url]} />
      </Field>
      {configure.isError && <ApiErrorAlert error={configure.error} />}
      <Button type="submit" disabled={form.formState.isSubmitting}>
        {configured ? "置き換える" : "設定する"}
      </Button>
    </form>
  );
}

function RemoveWebhook({ slug }: { slug: string }) {
  const [open, setOpen] = useState(false);
  const invalidate = useInvalidateTenant(slug);
  const remove = useRemoveSlackWebhook({ mutation: { onSuccess: () => invalidate() } });

  return (
    <div className="space-y-2 border-t pt-6">
      {remove.isError && <ApiErrorAlert error={remove.error} />}
      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogTrigger asChild>
          <Button variant="outline" disabled={remove.isPending}>
            通知をやめる
          </Button>
        </AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Slack への通知をやめますか？</AlertDialogTitle>
            <AlertDialogDescription>
              保存した URL を消します。また通知するときは、URL を入れ直してください。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>やめない</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                remove.mutate({ slug });
                setOpen(false);
              }}
            >
              通知をやめる
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
