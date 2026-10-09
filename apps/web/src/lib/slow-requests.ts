import type { QueryClient } from "@tanstack/react-query";

/**
 * 待たせている要求の様子。
 *
 * - `none` … 待っている要求がない、または待ち始めてから間もない
 * - `waiting` … 待ち始めてから数秒を超えた
 * - `retrying` … そのうえで、失敗した要求を試し直している（API Gateway の 504 のあとなど）
 */
export type SlowRequestState = "none" | "waiting" | "retrying";

/** 普段の読み込みは 1 秒もかからない。これを超えたら、止まっている DB を起こしている見込みが高い */
export const SLOW_REQUEST_DELAY_MS = 3000;

/**
 * TanStack Query の読み込み（クエリ）と保存（ミューテーション）をまとめて見張り、
 * 何かを待ち続けて `delayMs` を超えたら知らせる。止めるための関数を返す。
 *
 * 画面ごとではなくキャッシュ全体を見るため、スケルトンを出している画面も、ボタンを押したあとの保存も同じように拾う。
 * 待っている要求が一度なくなったら、数え直す。速い読み込みが続くだけでは、案内を出さない。
 */
export function watchSlowRequests(
  client: QueryClient,
  onChange: (state: SlowRequestState) => void,
  delayMs = SLOW_REQUEST_DELAY_MS,
): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let slow = false;
  let current: SlowRequestState = "none";

  const emit = (next: SlowRequestState) => {
    if (next === current) return;
    current = next;
    onChange(next);
  };

  const update = () => {
    if (client.isFetching() + client.isMutating() === 0) {
      clearTimeout(timer);
      timer = undefined;
      slow = false;
      emit("none");
      return;
    }
    if (!slow) {
      timer ??= setTimeout(() => {
        timer = undefined;
        slow = true;
        update();
      }, delayMs);
      return;
    }
    emit(isRetrying(client) ? "retrying" : "waiting");
  };

  const unsubscribeQueries = client.getQueryCache().subscribe(update);
  const unsubscribeMutations = client.getMutationCache().subscribe(update);
  update();

  return () => {
    unsubscribeQueries();
    unsubscribeMutations();
    clearTimeout(timer);
  };
}

/** 失敗の回数は、新しく読み込み始めると 0 に戻る。1 以上のまま待っているなら、試し直している */
function isRetrying(client: QueryClient) {
  return (
    client.isFetching({ predicate: (query) => query.state.fetchFailureCount > 0 }) +
      client.isMutating({ predicate: (mutation) => mutation.state.failureCount > 0 }) >
    0
  );
}
