import { type QueryKey, useQueryClient } from "@tanstack/react-query";

/**
 * カテゴリと難易度を、上下のボタンで並べ替える（DEV-71）。
 *
 * 並べ替えた ID の並びを、そのまま API に送る。API は、今ある項目をちょうど 1 回ずつ含む並びだけを受け付ける。
 */
export type Direction = -1 | 1;

/**
 * [index] の項目を、上（-1）か下（1）の隣と入れ替えた新しい並び。端にあって動かせなければ undefined。
 * [sameGroup] を渡すと、隣が同じ組（難易度なら同じレベル）のときだけ動かす
 */
export function moveItem<T>(
  items: readonly T[],
  index: number,
  direction: Direction,
  sameGroup?: (a: T, b: T) => boolean,
): T[] | undefined {
  const target = index + direction;
  if (index < 0 || index >= items.length || target < 0 || target >= items.length) return undefined;
  if (sameGroup && !sameGroup(items[index], items[target])) return undefined;
  const moved = [...items];
  [moved[index], moved[target]] = [moved[target], moved[index]];
  return moved;
}

/**
 * 並べ替えを、応答を待たずに一覧へ反映する。失敗したら元に戻し、成功しても失敗しても一覧を取り直す。
 * 並べ替えはたいてい送ったとおりに通る。応答を待つと、押すたびに画面が止まって見える。
 *
 * 生成したミューテーションの `onMutate` / `onError` / `onSettled` から呼ぶ。
 */
export function useOptimisticOrder<T extends { id: string }>(queryKey: QueryKey, refetch: () => Promise<unknown>) {
  const queryClient = useQueryClient();
  return {
    onMutate: async (ids: string[]) => {
      // 取り直しの途中なら止める。古い並びの応答が、反映した並びを上書きしないように
      await queryClient.cancelQueries({ queryKey });
      const previous = queryClient.getQueryData<T[]>(queryKey);
      if (previous) {
        const byId = new Map(previous.map((item) => [item.id, item]));
        queryClient.setQueryData<T[]>(
          queryKey,
          ids.flatMap((id) => byId.get(id) ?? []),
        );
      }
      return previous;
    },
    onError: (previous: T[] | undefined) => {
      if (previous) queryClient.setQueryData(queryKey, previous);
    },
    onSettled: () => refetch(),
  };
}
