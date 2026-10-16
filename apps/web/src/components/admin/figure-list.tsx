"use client";

import Link from "next/link";
import { useState } from "react";
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
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useDeleteFigure, useListFigures } from "@/lib/api/generated/endpoints";
import type { FigureSummaryResponse } from "@/lib/api/generated/model";
import { useInvalidateTenant } from "@/lib/admin/invalidate";

const dateFormat = new Intl.DateTimeFormat("ja-JP", { dateStyle: "medium", timeStyle: "short" });

const KIND_LABELS: Record<string, string> = { drawio: "draw.io の図", image: "画像", pdf: "PDF" };

/**
 * 解説図の一覧と、消す操作（DEV-166）。誤って上げたもの（個人の情報が写った画像など）を、管理者が消せるようにする。
 *
 * **公開中・下書きの問題が使っている図は消せない**（DEV-165）。先にその問題の解説から外してもらう。
 * ゴミ箱の問題だけが使っている図は消せるが、その問題はゴミ箱から戻せなくなる。確かめるときに示す。
 * 消した図は戻せない（論理削除ではない）
 */
export function FigureList({ slug }: { slug: string }) {
  const figures = useListFigures(slug);
  const invalidate = useInvalidateTenant(slug);
  const remove = useDeleteFigure({ mutation: { onSuccess: () => invalidate() } });
  const [target, setTarget] = useState<FigureSummaryResponse | null>(null);

  if (figures.isPending) return <Skeleton className="h-64 w-full" />;
  if (figures.isError) return <ApiErrorAlert error={figures.error} />;

  return (
    <Card>
      <CardHeader>
        <CardDescription>
          クイズの解説に入れた図・画像・PDF
          です。公開中か下書きの問題が使っている図は、その問題の解説から外すと消せます。 消した図は元に戻せません。
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {remove.error && <ApiErrorAlert error={remove.error} />}
        {figures.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">図はまだありません。</p>
        ) : (
          <ul className="divide-y">
            {figures.data.map((figure) => (
              <FigureItem
                key={figure.id}
                slug={slug}
                figure={figure}
                pending={remove.isPending}
                onDelete={() => {
                  remove.reset();
                  setTarget(figure);
                }}
              />
            ))}
          </ul>
        )}
      </CardContent>
      <DeleteFigureDialog
        figure={target}
        onOpenChange={(open) => !open && setTarget(null)}
        onConfirm={(figure) => {
          remove.mutate({ slug, id: figure.id });
          setTarget(null);
        }}
      />
    </Card>
  );
}

function FigureItem({
  slug,
  figure,
  pending,
  onDelete,
}: {
  slug: string;
  figure: FigureSummaryResponse;
  pending: boolean;
  onDelete: () => void;
}) {
  const inUse = figure.quizCount > 0;
  return (
    <li className="flex flex-wrap items-center gap-3 py-3">
      {/* 押すと新しいタブで開く。PDF は PDF を、ほかは図そのものを開く。図は白い背景に出す */}
      <a
        href={`/api/t/${slug}/play/figures/${figure.id}`}
        target="_blank"
        rel="noreferrer"
        className="flex h-16 w-24 shrink-0 items-center justify-center overflow-hidden rounded border bg-white"
      >
        {/* eslint-disable-next-line @next/next/no-img-element -- 署名付き URL へ送られる。Next.js の画像の最適化は通さない */}
        <img
          src={`/api/t/${slug}/play/figures/${figure.id}/preview`}
          alt={`${KIND_LABELS[figure.kind] ?? "図"}（${dateFormat.format(new Date(figure.createdAt))}）`}
          loading="lazy"
          className="max-h-full max-w-full object-contain"
        />
      </a>
      <div className="min-w-0 flex-1 text-sm">
        <p className="font-medium">{KIND_LABELS[figure.kind] ?? figure.kind}</p>
        <p className="text-xs text-muted-foreground">{dateFormat.format(new Date(figure.createdAt))} に置いた</p>
        <p className="text-xs text-muted-foreground">{usageText(figure)}</p>
        <UsingQuizzes slug={slug} figure={figure} />
      </div>
      <Button variant="destructive" size="sm" disabled={inUse || pending} onClick={onDelete}>
        消す
      </Button>
    </li>
  );
}

/**
 * 図を使っている問題（DEV-167）。公開中・下書きの問題は、解説から外せるよう編集画面へのリンクにする。
 * 削除済みの問題は編集できないので、リンクにしない
 */
function UsingQuizzes({ slug, figure }: { slug: string; figure: FigureSummaryResponse }) {
  if (figure.quizzes.length === 0) return null;
  return (
    <ul className="mt-1 space-y-0.5 text-xs">
      {figure.quizzes.map((quiz) => (
        <li key={quiz.id} className="truncate">
          {quiz.deleted ? (
            <span className="text-muted-foreground">{quiz.question}（削除済み）</span>
          ) : (
            <Link href={`/t/${slug}/admin/quizzes/${quiz.id}`} className="underline underline-offset-4">
              {quiz.question}
            </Link>
          )}
        </li>
      ))}
    </ul>
  );
}

function usageText(figure: FigureSummaryResponse): string {
  const parts = [];
  if (figure.quizCount > 0) parts.push(`公開中・下書きの問題 ${figure.quizCount} 件で使用中。解説から外すと消せます`);
  if (figure.trashedQuizCount > 0) parts.push(`削除済みの問題 ${figure.trashedQuizCount} 件が使用`);
  return parts.length > 0 ? parts.join("・") : "どの問題も使っていません";
}

function DeleteFigureDialog({
  figure,
  onOpenChange,
  onConfirm,
}: {
  figure: FigureSummaryResponse | null;
  onOpenChange: (open: boolean) => void;
  onConfirm: (figure: FigureSummaryResponse) => void;
}) {
  return (
    <AlertDialog open={figure !== null} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>この{figure ? (KIND_LABELS[figure.kind] ?? "図") : "図"}を消しますか？</AlertDialogTitle>
          <AlertDialogDescription asChild>
            <div className="space-y-2">
              <p>消した図は元に戻せません。</p>
              {figure && figure.trashedQuizCount > 0 && (
                <p>
                  この図を使っている削除済みの問題 {figure.trashedQuizCount} 件は、削除済み一覧から戻せなくなります。
                </p>
              )}
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>やめる</AlertDialogCancel>
          <Button variant="destructive" onClick={() => figure && onConfirm(figure)}>
            消す
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
