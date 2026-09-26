"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { Check, Copy } from "lucide-react";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useCreateInvitation, useListInvitations, useRevokeInvitation } from "@/lib/api/generated/endpoints";
import type { CreatedInvitationResponse } from "@/lib/api/generated/model";
import { useInvalidateTenant } from "@/lib/admin/invalidate";
import {
  type InvitationFormInput,
  InvitationFormSchema,
  type InvitationFormValues,
  invitationUrl,
} from "@/lib/admin/invitation-form";
import { roleLabel } from "@/lib/auth/roles";

const dateFormat = new Intl.DateTimeFormat("ja-JP", { dateStyle: "medium", timeStyle: "short" });

/**
 * 招待（要件定義 A6）。**メールは送らない。** 作ったリンクを管理者がコピーして相手に渡す（ADR-0016）。
 *
 * リンクは作った直後にしか出せない。バックエンドはトークンのハッシュしか持たないため。
 * なくしたら、同じアドレスへ招待し直す（前のリンクは使えなくなる）。
 */
export function InvitationManager({ slug }: { slug: string }) {
  const [created, setCreated] = useState<CreatedInvitationResponse | null>(null);
  return (
    <div className="space-y-6">
      <NewInvitationForm slug={slug} onCreated={setCreated} />
      {created && <CreatedInvitation created={created} onClose={() => setCreated(null)} />}
      <OpenInvitations slug={slug} />
    </div>
  );
}

function NewInvitationForm({
  slug,
  onCreated,
}: {
  slug: string;
  onCreated: (created: CreatedInvitationResponse) => void;
}) {
  const invalidate = useInvalidateTenant(slug);
  const form = useForm<InvitationFormInput, unknown, InvitationFormValues>({
    resolver: zodResolver(InvitationFormSchema),
    defaultValues: { email: "", role: "member" },
  });
  const { errors } = form.formState;
  const createInvitation = useCreateInvitation();

  const submit = form.handleSubmit(async (values) => {
    // 失敗は createInvitation.error として画面に出る
    const created = await createInvitation.mutateAsync({ slug, data: values }).catch(() => null);
    if (!created) return;
    onCreated(created);
    form.reset();
    await invalidate();
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>招待する</CardTitle>
        <CardDescription>
          招待のリンクを作ります。招待したメールアドレスでログインした人だけが、リンクから参加できます。
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="grid gap-4 sm:grid-cols-[1fr_10rem]">
          <Field data-invalid={!!errors.email}>
            <FieldLabel htmlFor="invitation-email">メールアドレス</FieldLabel>
            <Input
              id="invitation-email"
              type="email"
              autoComplete="off"
              aria-invalid={!!errors.email}
              {...form.register("email")}
            />
            <FieldError errors={[errors.email]} />
          </Field>
          <Field data-invalid={!!errors.role}>
            <FieldLabel>ロール</FieldLabel>
            <Controller
              control={form.control}
              name="role"
              render={({ field }) => (
                <Select value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger aria-invalid={!!errors.role}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="member">{roleLabel("member")}</SelectItem>
                    <SelectItem value="admin">{roleLabel("admin")}</SelectItem>
                  </SelectContent>
                </Select>
              )}
            />
            <FieldError errors={[errors.role]} />
          </Field>
          <div className="space-y-2 sm:col-span-2">
            {createInvitation.isError && <ApiErrorAlert error={createInvitation.error} />}
            <Button type="submit" disabled={form.formState.isSubmitting}>
              リンクを作る
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

function CreatedInvitation({ created, onClose }: { created: CreatedInvitationResponse; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  const url = invitationUrl(created.token);
  const { invitation } = created;

  const copy = async () => {
    await navigator.clipboard.writeText(url);
    setCopied(true);
  };

  return (
    <Card className="border-primary">
      <CardHeader>
        <CardTitle>招待のリンクができました</CardTitle>
        <CardDescription>
          {invitation.email} の人に渡してください（{roleLabel(invitation.role)}、
          {dateFormat.format(new Date(invitation.expiresAt))} まで有効）。
          <strong className="text-foreground">このリンクは、いまだけ表示されます。</strong>
          なくしたときは、同じアドレスへ招待し直してください。
        </CardDescription>
        <CardAction>
          <Button variant="ghost" size="sm" onClick={onClose}>
            閉じる
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="flex gap-2">
        <Input readOnly value={url} aria-label="招待のリンク" onFocus={(event) => event.target.select()} />
        <Button variant="outline" onClick={copy} className="shrink-0">
          {copied ? <Check /> : <Copy />}
          {copied ? "コピーしました" : "コピー"}
        </Button>
      </CardContent>
    </Card>
  );
}

/** 受け入れを待っている招待。受け入れられたものは所属になり、ここからは消える */
function OpenInvitations({ slug }: { slug: string }) {
  const invitations = useListInvitations(slug);
  const invalidate = useInvalidateTenant(slug);
  const revoke = useRevokeInvitation({ mutation: { onSuccess: () => invalidate() } });

  if (invitations.isPending) return <Skeleton className="h-40 w-full" />;
  if (invitations.isError) return <ApiErrorAlert error={invitations.error} />;
  if (invitations.data.length === 0) {
    return <p className="text-muted-foreground text-sm">受け入れを待っている招待はありません。</p>;
  }

  return (
    <div className="space-y-2">
      {revoke.isError && <ApiErrorAlert error={revoke.error} />}
      <div className="bg-background rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>メールアドレス</TableHead>
              <TableHead className="hidden sm:table-cell">ロール</TableHead>
              <TableHead className="hidden sm:table-cell">期限</TableHead>
              <TableHead className="w-20" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {invitations.data.map((invitation) => {
              const expired = invitation.status === "expired";
              const expiry = expired ? (
                <Badge variant="outline">期限切れ</Badge>
              ) : (
                `${dateFormat.format(new Date(invitation.expiresAt))} まで`
              );
              return (
                <TableRow key={invitation.id}>
                  <TableCell className="max-w-0 sm:max-w-none">
                    <p className="truncate font-medium">{invitation.email}</p>
                    {/* 狭い幅ではロールと期限の列を畳み、アドレスの下に出す */}
                    <p className="text-muted-foreground text-xs sm:hidden">
                      {roleLabel(invitation.role)}・{expiry}
                    </p>
                  </TableCell>
                  <TableCell className="hidden sm:table-cell">{roleLabel(invitation.role)}</TableCell>
                  <TableCell className="text-muted-foreground hidden text-sm sm:table-cell">{expiry}</TableCell>
                  <TableCell className="text-right">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={revoke.isPending}
                      onClick={() => revoke.mutate({ slug, id: invitation.id })}
                    >
                      取り消す
                    </Button>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
