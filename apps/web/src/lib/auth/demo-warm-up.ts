import { newTraceParent } from "@/lib/trace-context";

/**
 * 止まっている DB を、デモに入る前に起こし始める（DEV-195）。**サーバーでだけ使う。**
 *
 * Aurora は使われないと一時停止し（min 0 ACU）、止まったあとの最初の要求は復帰（15〜20 秒）を待つ。
 * 「デモとして入る」の直後の要求で待たせないよう、入口を表示した時点で、API の起こす口（`POST /api/warm-up`）を呼ぶ。
 * 入口を読んでいる間に、復帰が進む。
 *
 * - **起こす回数の上限は API が持つ**（DEV-217。1 日 20 回、quiz-service の `DatabaseWarmUp`）。
 *   スクリプトを動かすクローラーが入口を開くたびに起こすと、Aurora が一時停止しなくなる。
 *   web のサーバーは実行環境ごとに分かれ、入れ替わるため、ここでは全体の回数を数えられない
 * - トークンは付けない。認証すると利用者を DB から引くため、上限を確かめる前に DB が起きてしまう
 * - **間隔を空ける。** 前に呼んでから {@link WARM_UP_INTERVAL_MS} の間は呼ばない。入口を開いた人の数だけ API を呼ばない
 * - 結果は待つが、使わない。失敗しても（止まっているタスク、上限）、デモに入るときの要求が起こす
 */
export const WARM_UP_INTERVAL_MS = 60_000;

/** API Gateway の待ち時間の上限（30 秒）に合わせる。それより先は、待っても応答が来ない */
const TIMEOUT_MS = 30_000;

export function createDemoWarmUp(apiOrigin: () => string, now: () => number = Date.now) {
  let last: number | undefined;

  /** 呼んだら true。間隔の中で呼ばなかったら false */
  return async function warmUp(): Promise<boolean> {
    if (last !== undefined && now() - last < WARM_UP_INTERVAL_MS) return false;
    last = now();
    try {
      const response = await fetch(new URL("/api/warm-up", apiOrigin()), {
        method: "POST",
        headers: { traceparent: newTraceParent() },
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      if (!response.ok) console.warn("DB を先に起こせませんでした", response.status);
    } catch (error) {
      console.warn("DB を先に起こせませんでした", error instanceof Error ? error.name : error);
    }
    return true;
  };
}

/** サーバーで 1 つだけ持つ。Amplify の SSR では、サーバー（実行環境）ごとに 1 つになる */
export const demoWarmUp = createDemoWarmUp(() => process.env.API_ORIGIN ?? "http://localhost:8080");
