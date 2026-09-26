"use client";

import Link from "next/link";
import { useInfiniteQuery } from "@tanstack/react-query";
import { ChevronRight } from "lucide-react";
import { ApiErrorAlert } from "@/components/api-error-alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { getListCompletedAttemptsQueryKey, listCompletedAttempts } from "@/lib/api/generated/endpoints";
import type { AttemptHistoryItem } from "@/lib/api/generated/model";
import { scopeLabel } from "@/lib/play/scope";

const dateFormat = new Intl.DateTimeFormat("ja-JP", { dateStyle: "medium", timeStyle: "short" });

/**
 * 完了した挑戦の一覧。新しい順に、続きは「もっと見る」で読む。
 *
 * 各行から結果画面を開く。完了した挑戦には完了の API が結果を返すので、そのまま使える。
 * 振り返りのために開くので、学習モードでも全問の解説を出す（模試モード）。
 */
export function AttemptHistory({ slug }: { slug: string }) {
  const history = useInfiniteQuery({
    // 生成したフックと同じキーにすると、ページを持たない形のキャッシュと取り違える
    queryKey: [...getListCompletedAttemptsQueryKey(slug), "infinite"],
    queryFn: ({ pageParam, signal }) =>
      listCompletedAttempts(slug, pageParam ? { cursor: pageParam } : undefined, { signal }),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor ?? null,
  });

  if (history.isPending) return <Skeleton className="h-60 w-full" />;
  if (history.isLoadingError) return <ApiErrorAlert error={history.error} />;

  const items = history.data.pages.flatMap((page) => page.items);
  if (items.length === 0) {
    return (
      <p className="text-muted-foreground text-sm">
        まだ最後まで解いたクイズがありません。
        <Link href={`/t/${slug}/play`} className="text-foreground underline underline-offset-4">
          解いてみる
        </Link>
      </p>
    );
  }

  return (
    <div className="space-y-3">
      <ul className="divide-y rounded-lg border">
        {items.map((item) => (
          <AttemptRow key={item.id} slug={slug} item={item} />
        ))}
      </ul>
      {/* 続きの読み込みに失敗しても、読めた分は残す */}
      {history.isRefetchError && <ApiErrorAlert error={history.error} />}
      {history.hasNextPage && (
        <Button
          variant="outline"
          className="w-full"
          disabled={history.isFetchingNextPage}
          onClick={() => history.fetchNextPage()}
        >
          {history.isFetchingNextPage ? "読み込み中…" : "もっと見る"}
        </Button>
      )}
    </div>
  );
}

function AttemptRow({ slug, item }: { slug: string; item: AttemptHistoryItem }) {
  const { totalCount, answeredCount, correctCount } = item;
  // 結果画面と同じく、出題数を分母にする。解かずに終えた問題は不正解と同じ扱い
  const rate = totalCount === 0 ? 0 : Math.round((correctCount / totalCount) * 100);

  return (
    <li>
      <Link
        href={`/t/${slug}/play/result?attempt=${item.id}&mode=exam`}
        className="hover:bg-accent flex items-center gap-3 p-4"
      >
        <div className="min-w-0 flex-1 space-y-1">
          <p className="font-medium break-words">{attemptTitle(item)}</p>
          <p className="text-muted-foreground text-xs">
            {dateFormat.format(new Date(item.finishedAt))}・{scopeLabel(item.scope)}
            {answeredCount < totalCount && `・未回答 ${totalCount - answeredCount} 問`}
          </p>
        </div>
        <div className="shrink-0 text-right">
          <p className="font-semibold tabular-nums">{rate}%</p>
          <p className="text-muted-foreground text-xs tabular-nums">
            {correctCount} / {totalCount}
          </p>
        </div>
        <ChevronRight className="text-muted-foreground size-4 shrink-0" />
      </Link>
    </li>
  );
}

/**
 * 何を解いたか。名前が返らないのは、そのカテゴリや難易度がいま出題されていないとき（削除・非公開）。
 * 名前は挑戦に記録していないので、消えたものの名前は出せない。
 */
function attemptTitle(item: AttemptHistoryItem): string {
  const category =
    item.categoryId == null ? "すべてのカテゴリ" : (item.categoryName ?? "出題されていないカテゴリ");
  const range =
    item.difficultyId != null
      ? (item.difficultyName ?? "出題されていない難易度")
      : item.level != null
        ? `レベル ${item.level} のすべて`
        : "すべての難易度";
  return `${category}・${range}`;
}
