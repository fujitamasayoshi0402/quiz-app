"use client";

import { useState } from "react";
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
import { Skeleton } from "@/components/ui/skeleton";
import { useGetTenantSettings, useUpdateTenantSettings } from "@/lib/api/generated/endpoints";
import { useInvalidateTenant } from "@/lib/admin/invalidate";

/** 切り替える先ごとの、確かめる文言。**何が起きるかを、切り替える前に示す**（ADR-0025） */
const SWITCH = {
  public: {
    action: "公開する",
    title: "テナントを公開しますか？",
    description:
      "ログインした人なら誰でも、公開テナントの一覧からこのテナントを見つけ、一般ユーザーとして参加できるようになります。参加した人は、公開中のクイズを解けます。",
  },
  private: {
    action: "非公開に戻す",
    title: "テナントを非公開に戻しますか？",
    description:
      "一覧に出なくなり、招待した人だけが参加できるようになります。公開している間に参加した人は、所属したまま残ります。",
  },
} as const;

/** テナントの公開設定（ADR-0025）。いまの状態と、切り替えのボタンを出す */
export function TenantSettings({ slug }: { slug: string }) {
  const settings = useGetTenantSettings(slug);

  if (settings.isPending) return <Skeleton className="h-48 w-full" />;
  if (settings.isError) return <ApiErrorAlert error={settings.error} />;

  const isPublic = settings.data.visibility === "public";
  return (
    <Card>
      <CardHeader>
        <CardTitle>公開設定</CardTitle>
        <CardDescription>
          非公開のテナントには、招待した人だけが参加できます。公開すると、ログインした人が一覧から見つけて参加できます。
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        <div className="flex items-center gap-2 text-sm">
          いまの状態 {isPublic ? <Badge>公開</Badge> : <Badge variant="outline">非公開</Badge>}
        </div>
        <SwitchVisibility slug={slug} to={isPublic ? "private" : "public"} />
      </CardContent>
    </Card>
  );
}

function SwitchVisibility({ slug, to }: { slug: string; to: keyof typeof SWITCH }) {
  const [open, setOpen] = useState(false);
  const invalidate = useInvalidateTenant(slug);
  const update = useUpdateTenantSettings({ mutation: { onSuccess: () => invalidate() } });
  const text = SWITCH[to];

  return (
    <div className="space-y-2">
      {update.isError && <ApiErrorAlert error={update.error} />}
      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogTrigger asChild>
          <Button variant={to === "public" ? "default" : "outline"} disabled={update.isPending}>
            {text.action}
          </Button>
        </AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{text.title}</AlertDialogTitle>
            <AlertDialogDescription>{text.description}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>やめる</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                update.mutate({ slug, data: { visibility: to } });
                setOpen(false);
              }}
            >
              {text.action}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
