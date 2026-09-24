"use client";

import { useQueries } from "@tanstack/react-query";
import { getListDifficultiesQueryOptions, useListCategories } from "@/lib/api/generated/endpoints";
import type { CategoryResponse, DifficultyResponse } from "@/lib/api/generated/model";

/**
 * カテゴリと、その配下の難易度をまとめて引く。一覧で ID を名前に直すために使う。
 *
 * 難易度はカテゴリ配下のリソースで、一括で返す API がない。カテゴリの数だけ並列に問い合わせる。
 * 1 テナントのカテゴリは多くても数十の想定なので、専用の API は作っていない。
 */
export function useCatalog(slug: string) {
  const categories = useListCategories(slug);
  const difficulties = useQueries({
    queries: (categories.data ?? []).map((category) => getListDifficultiesQueryOptions(slug, category.id)),
  });

  const difficultiesByCategory = new Map<string, DifficultyResponse[]>(
    (categories.data ?? []).map((category, index) => [category.id, difficulties[index]?.data ?? []]),
  );
  const categoryName = new Map((categories.data ?? []).map((c: CategoryResponse) => [c.id, c.name]));
  const difficultyName = new Map(
    [...difficultiesByCategory.values()].flat().map((d) => [d.id, d.name] as const),
  );

  return {
    categories,
    difficultiesOf: (categoryId: string | undefined) =>
      categoryId ? (difficultiesByCategory.get(categoryId) ?? []) : [],
    categoryName: (id: string) => categoryName.get(id) ?? "（不明）",
    difficultyName: (id: string) => difficultyName.get(id) ?? "（不明）",
  };
}
