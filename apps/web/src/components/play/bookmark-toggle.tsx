"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Bookmark } from "lucide-react";
import { Button } from "@/components/ui/button";
import { addBookmark, getListBookmarksQueryKey, removeBookmark, useListBookmarks } from "@/lib/api/generated/endpoints";
import { cn } from "@/lib/utils";

/**
 * 「あとで見直す」の印を付け外しする（DEV-159）。印の付いた問題は、出題対象「あとで見直す問題」で解き直せる。
 *
 * **押したらすぐに表示を切り替え、失敗したら戻す。** 解いている途中で押すので、通信を待たせない。
 * 送っている間は送った値を出し、終わったら一覧を取り直す。一覧のキャッシュは書き換えない。
 * 一覧はカテゴリと難易度も持ち、結果画面の問題からは埋められないため。
 * 付け外しは何度送っても同じ結果になる API のため、続けて押されても印はずれない。
 */
export function BookmarkToggle({ slug, quizId, className }: { slug: string; quizId: string; className?: string }) {
  const queryClient = useQueryClient();
  const bookmarks = useListBookmarks(slug, { query: { staleTime: 60_000 } });
  const saved = bookmarks.data?.items.some((item) => item.quizId === quizId) ?? false;

  const toggle = useMutation({
    mutationFn: (next: boolean) => (next ? addBookmark(slug, quizId) : removeBookmark(slug, quizId)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: getListBookmarksQueryKey(slug) }),
  });
  const on = toggle.isPending ? toggle.variables : saved;

  return (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      aria-pressed={on}
      disabled={bookmarks.isPending}
      onClick={(event) => {
        // テンポモードでは、カードを押すと解説が開く。印を押したときは開かない
        event.stopPropagation();
        toggle.mutate(!on);
      }}
      className={cn("h-auto gap-1 px-2 py-1 text-xs", on ? "text-foreground" : "text-muted-foreground", className)}
    >
      <Bookmark className={cn("size-4", on && "fill-current")} />
      あとで見直す
    </Button>
  );
}
