"use client";

import { useState } from "react";
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
import { Button } from "@/components/ui/button";

/**
 * 削除の確認。**配下に何があるかを見せてから消す**（要件定義「削除の扱い」）。
 *
 * `impact` は開いたときにだけ描画する。影響範囲の問い合わせを、確認を求める瞬間まで遅らせるため。
 * 配下が 0 件でも確認は挟む。
 */
export function DeleteDialog({
  name,
  impact,
  onConfirm,
  pending,
  size = "sm",
}: {
  name: string;
  impact?: React.ReactNode;
  onConfirm: () => void;
  pending?: boolean;
  size?: "sm" | "default";
}) {
  const [open, setOpen] = useState(false);
  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger asChild>
        <Button variant="destructive" size={size} disabled={pending}>
          削除
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>「{name}」を削除しますか？</AlertDialogTitle>
          <AlertDialogDescription asChild>
            <div className="space-y-2">
              {open && impact}
              <p>削除済み一覧から復活できます。</p>
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>やめる</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            onClick={() => {
              onConfirm();
              setOpen(false);
            }}
          >
            削除する
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** 配下の件数。「これらもあわせて削除されます」と添える */
export function ImpactSummary({
  loading,
  difficultyCount,
  quizCount,
}: {
  loading: boolean;
  difficultyCount?: number;
  quizCount?: number;
}) {
  if (loading) return <p>配下の件数を確認しています…</p>;
  return (
    <div>
      <p>次もあわせて削除されます。</p>
      <ul className="list-disc pl-5">
        {difficultyCount !== undefined && <li>難易度 {difficultyCount} 件</li>}
        <li>クイズ {quizCount ?? 0} 件</li>
      </ul>
    </div>
  );
}
