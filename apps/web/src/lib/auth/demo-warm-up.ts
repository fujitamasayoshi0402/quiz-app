import { demoTokens } from "@/lib/auth/demo";
import type { TokenSet } from "@/lib/auth/oidc";
import { newTraceParent } from "@/lib/trace-context";

/**
 * 止まっている DB を、デモに入る前に起こし始める（DEV-195）。**サーバーでだけ使う。**
 *
 * Aurora は使われないと一時停止し（min 0 ACU）、止まったあとの最初の要求は復帰（15〜20 秒）を待つ。
 * 「デモとして入る」の直後の要求で待たせないよう、入口を表示した時点で、デモのアカウントのトークンで API を 1 回呼ぶ。
 * 入口を読んでいる間に、復帰が進む。
 *
 * - 呼ぶのは `/api/me/tenants`。認証した要求は、利用者を DB から引くため、DB を起こす。デモに入った直後にトップが呼ぶものと同じ。
 *   ヘルスチェック（アクチュエータ）は DB に触れない決まりで、API Gateway も通さない
 * - **間隔を空ける。** 前に呼んでから {@link WARM_UP_INTERVAL_MS} の間は呼ばない。入口を開いた人の数だけ API を呼ばない
 * - 結果は待つが、使わない。失敗しても（止まっているタスク、復帰の待ちの時間切れ）、デモに入るときの要求がもう一度起こす
 */
export const WARM_UP_INTERVAL_MS = 60_000;

/** API Gateway の待ち時間の上限（30 秒）に合わせる。それより先は、待っても応答が来ない */
const TIMEOUT_MS = 30_000;

export function createDemoWarmUp(
  tokens: () => Promise<TokenSet>,
  apiOrigin: () => string,
  now: () => number = Date.now,
) {
  let last: number | undefined;

  /** 呼んだら true。間隔の中で呼ばなかったら false */
  return async function warmUp(): Promise<boolean> {
    if (last !== undefined && now() - last < WARM_UP_INTERVAL_MS) return false;
    last = now();
    try {
      const { accessToken } = await tokens();
      const response = await fetch(new URL("/api/me/tenants", apiOrigin()), {
        headers: { authorization: `Bearer ${accessToken}`, traceparent: newTraceParent() },
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
export const demoWarmUp = createDemoWarmUp(demoTokens, () => process.env.API_ORIGIN ?? "http://localhost:8080");
