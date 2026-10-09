"use client";

import { useQueryClient } from "@tanstack/react-query";

/**
 * テナントの API のキャッシュをまとめて捨てる。管理画面で何かを変えたあとに呼ぶ。
 *
 * 生成したクエリキーは URL そのもの（`/api/t/{slug}/admin/quizzes` など）で、前方一致で絞れない。
 * どの一覧に影響するかを変更ごとに列挙すると漏れるため、テナント単位で粗く捨てる。
 * 管理画面の操作頻度では、取り直しの費用は問題にならない。
 */
export function useInvalidateTenant(slug: string) {
  const queryClient = useQueryClient();
  const prefix = `/api/t/${slug}/`;
  return () =>
    queryClient.invalidateQueries({
      predicate: (query) => typeof query.queryKey[0] === "string" && query.queryKey[0].startsWith(prefix),
    });
}
