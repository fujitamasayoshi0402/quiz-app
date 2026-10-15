"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { ApiErrorAlert } from "@/components/api-error-alert";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { getListMyTenantsQueryKey, useLeaveTenant } from "@/lib/api/generated/endpoints";

/**
 * テナントからの退会の確認（ADR-0030）。**履歴が消えて戻らないことを示してから退会する。**
 *
 * 最後の管理者と共有のアカウントは、退会を押すとバックエンドが理由を返す（409）。ダイアログを閉じずに、その理由を出す。
 * 退会したら所属の一覧（`/`）へ移す。このテナントの画面は、もう開けない
 */
export function LeaveTenantDialog({
  slug,
  name,
  open,
  onOpenChange,
}: {
  slug: string;
  name: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const leave = useLeaveTenant();

  const confirm = async () => {
    // 失敗は leave.error としてダイアログに出る
    const left = await leave.mutateAsync({ slug }).then(
      () => true,
      () => false,
    );
    if (!left) return;
    await queryClient.invalidateQueries({ queryKey: getListMyTenantsQueryKey() });
    onOpenChange(false);
    router.replace("/");
  };

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (!next) leave.reset();
        onOpenChange(next);
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>「{name}」から退会しますか？</AlertDialogTitle>
          <AlertDialogDescription asChild>
            <div className="space-y-2">
              <p>このテナントでの回答の履歴と、ランキングへの参加が消えます。元には戻せません。</p>
              <p>もう一度入るには、公開されているテナントなら一覧から参加し、そうでなければ招待を受け直します。</p>
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        {leave.error && <ApiErrorAlert error={leave.error} />}
        <AlertDialogFooter>
          <AlertDialogCancel>やめる</AlertDialogCancel>
          <Button variant="destructive" onClick={confirm} disabled={leave.isPending}>
            退会する
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
