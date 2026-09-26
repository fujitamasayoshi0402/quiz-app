"use client";

import { useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import type { ReactNode } from "react";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { SignOutButton } from "@/components/tenant/sign-out-button";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError } from "@/lib/api/fetcher";
import {
  getListMyTenantsQueryKey,
  useAcceptInvitation,
  useGetReceivedInvitation,
} from "@/lib/api/generated/endpoints";
import { roleLabel } from "@/lib/auth/roles";

const dateFormat = new Intl.DateTimeFormat("ja-JP", { dateStyle: "medium", timeStyle: "short" });

/**
 * 招待を受け入れる（要件定義 U1）。招待先を見せてから、ボタンで参加させる。
 *
 * リンクを開いただけでは参加させない。受け入れは状態を変える操作なので、利用者の操作（POST）を待つ。
 * 招待されたのと別のアドレスでログインしていると、バックエンドが 403 を返す。アカウントを替えるよう案内する。
 */
export function InvitationAcceptance({ token, email }: { token: string; email?: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const invitation = useGetReceivedInvitation(token);
  const accept = useAcceptInvitation({
    mutation: {
      onSuccess: async (tenant) => {
        await queryClient.invalidateQueries({ queryKey: getListMyTenantsQueryKey() });
        router.push(`/t/${tenant.slug}`);
      },
      // 開いてから押すまでの間に期限が切れた、取り消されたなど。いまの状態を出し直す
      onError: () => invitation.refetch(),
    },
  });

  if (invitation.isPending) return <Skeleton className="h-48 w-full" />;

  const signedInAs = (
    <CardFooter className="text-muted-foreground flex flex-wrap items-center justify-between gap-2 text-sm">
      <span className="truncate">{email} でログインしています</span>
      <SignOutButton />
    </CardFooter>
  );

  if (invitation.isError) {
    const status = invitation.error instanceof ApiError ? invitation.error.status : undefined;
    if (status === 403) {
      return (
        <Notice
          title="この招待は、ログインしているアカウント宛てではありません"
          description="招待されたメールアドレスでログインし直してから、このリンクをもう一度開いてください。"
          footer={signedInAs}
        />
      );
    }
    if (status === 404) {
      return (
        <Notice
          title="招待が見つかりません"
          description="リンクが途中で切れていないか確かめてください。"
          footer={signedInAs}
        />
      );
    }
    return <ApiErrorAlert error={invitation.error} />;
  }

  const { tenantName, role, status, expiresAt } = invitation.data;
  if (status === "accepted") {
    return (
      <Notice
        title="この招待は使用済みです"
        description="すでに参加していれば、テナントの一覧から入れます。"
        footer={signedInAs}
        action={
          <Button asChild variant="outline">
            <Link href="/">テナントの一覧へ</Link>
          </Button>
        }
      />
    );
  }
  if (status !== "pending") {
    return (
      <Notice
        title={status === "expired" ? "この招待は期限が切れています" : "この招待は取り消されています"}
        description="招待した人に、招待し直してもらってください。"
        footer={signedInAs}
      />
    );
  }

  return (
    <Notice
      title={`${tenantName} への招待`}
      description={`${roleLabel(role)}として招待されています。${dateFormat.format(new Date(expiresAt))} まで有効です。`}
      footer={signedInAs}
      action={
        <div className="space-y-3">
          {accept.isError && <ApiErrorAlert error={accept.error} />}
          <Button className="w-full" disabled={accept.isPending} onClick={() => accept.mutate({ token })}>
            参加する
          </Button>
        </div>
      }
    />
  );
}

function Notice({
  title,
  description,
  action,
  footer,
}: {
  title: string;
  description: string;
  action?: ReactNode;
  footer: ReactNode;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      {action && <CardContent>{action}</CardContent>}
      {footer}
    </Card>
  );
}
