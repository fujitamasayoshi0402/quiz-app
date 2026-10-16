"use client";

import { useListAllDifficulties, useListCategories } from "@/lib/api/generated/endpoints";
import type { CategoryResponse } from "@/lib/api/generated/model";

/**
 * カテゴリと、その配下の難易度をまとめて引く。一覧で ID を名前に直すために使う。
 *
 * 難易度は、カテゴリをまたいだ一覧（`listAllDifficulties`）で 1 回で取る。
 * カテゴリの数だけ問い合わせると、管理画面で何かを変えて読み直すたびに要求がまとめて出て、
 * 利用者ごとの流量の上限（バースト 30）に当たる（DEV-144）
 */
export function useCatalog(slug: string) {
  const categories = useListCategories(slug);
  const difficulties = useListAllDifficulties(slug);

  const difficultiesByCategory = Map.groupBy(difficulties.data ?? [], (difficulty) => difficulty.categoryId);
  const categoryName = new Map((categories.data ?? []).map((c: CategoryResponse) => [c.id, c.name]));
  const difficultyName = new Map((difficulties.data ?? []).map((d) => [d.id, d.name] as const));

  return {
    categories,
    difficultiesOf: (categoryId: string | undefined) =>
      categoryId ? (difficultiesByCategory.get(categoryId) ?? []) : [],
    categoryName: (id: string) => categoryName.get(id) ?? "（不明）",
    difficultyName: (id: string) => difficultyName.get(id) ?? "（不明）",
  };
}
