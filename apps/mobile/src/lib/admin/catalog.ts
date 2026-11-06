import { useQueryClient } from "@tanstack/react-query";
import { useListAllDifficulties, useListCategories } from "@/lib/api/generated/endpoints";

/**
 * カテゴリと、その配下の難易度をまとめて引く（DEV-216。web の `lib/admin/catalog.ts` から写した）。
 *
 * 難易度は、カテゴリをまたいだ一覧で 1 回で取る。カテゴリの数だけ問い合わせると、
 * 利用者ごとの流量の上限（バースト 30）に当たる（DEV-144）
 */
export function useCatalog(slug: string) {
  const categories = useListCategories(slug);
  const difficulties = useListAllDifficulties(slug);

  const categoryNames = new Map((categories.data ?? []).map((c) => [c.id, c.name]));
  const difficultyNames = new Map((difficulties.data ?? []).map((d) => [d.id, d.name]));

  return {
    categories,
    difficultiesOf: (categoryId: string) => (difficulties.data ?? []).filter((d) => d.categoryId === categoryId),
    categoryName: (id: string) => categoryNames.get(id) ?? "（不明）",
    difficultyName: (id: string) => difficultyNames.get(id) ?? "（不明）",
  };
}

/**
 * テナントの API のキャッシュをまとめて捨てる（web の `lib/admin/invalidate.ts` と同じ）。管理の画面で何かを変えたあとに呼ぶ。
 * 生成したクエリキーは URL そのもので、どの一覧に影響するかを変更ごとに列挙すると漏れる
 */
export function useInvalidateTenant(slug: string) {
  const client = useQueryClient();
  const prefix = `/api/t/${slug}/`;
  return () =>
    client.invalidateQueries({
      predicate: (query) => typeof query.queryKey[0] === "string" && query.queryKey[0].startsWith(prefix),
    });
}
