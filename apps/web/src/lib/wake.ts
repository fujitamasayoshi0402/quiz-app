/**
 * 止まっているバックエンドを起こし、使えるようになるまで待つ（DEV-154）。
 *
 * dev のバックエンドは、使われなくなると止まる。`POST /api/wake` は API Gateway が受け、バックエンドを通らずに起こす
 * （`infra/terraform/modules/quiz-service/wake.tf`）。起きるまで 2〜3 分かかるため、届くようになるまで間をおいて確かめる。
 *
 * 画面に止まっている旨の案内がいくつ並んでも、起こして待つのは 1 回にする。
 */

/** 起こして待った結果 */
export type WakeResult = "ready" | "failed" | "timeout";

/** 届いたかを確かめる要求。軽く、ログインしていれば誰でも読める（所属の一覧。どの画面も最初に読む） */
const PROBE_PATH = "/api/me/tenants";

/**
 * バックエンドまで届いた応答か。
 *
 * バックエンドの応答は、エラーも ProblemDetail で返る。手前の API Gateway が返す応答（送り先がない 500 / 503、
 * 時間切れの 504）は `{"message": ...}` の形で、まだ起動の途中にある
 */
export function reachedBackend(response: Pick<Response, "ok" | "headers">): boolean {
  return response.ok || (response.headers.get("content-type")?.startsWith("application/problem+json") ?? false);
}

type WaitOptions = {
  probe: () => Promise<Pick<Response, "ok" | "headers">>;
  intervalMs: number;
  timeoutMs: number;
};

/** バックエンドに届くまで、間をおいて確かめる。`timeoutMs` を過ぎたら諦める */
export async function waitForBackend({ probe, intervalMs, timeoutMs }: WaitOptions): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      if (reachedBackend(await probe())) return true;
    } catch {
      // 通信の失敗も、起動の途中として待つ
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  return false;
}

let inflight: Promise<WakeResult> | null = null;

/**
 * 起こして、使えるようになるまで待つ。待っている間に呼ばれたら、同じ結果を返す。
 *
 * 起こす口がない環境（ローカル、prod）では、`/api/wake` がバックエンドに届かないか 404 になり、`failed` を返す
 */
export function wakeBackend(
  options: Partial<WaitOptions> & { request?: () => Promise<Pick<Response, "ok">> } = {},
): Promise<WakeResult> {
  inflight ??= run(options).finally(() => {
    inflight = null;
  });
  return inflight;
}

async function run({
  request = () => fetch("/api/wake", { method: "POST" }),
  probe = () => fetch(PROBE_PATH),
  intervalMs = 10_000,
  timeoutMs = 5 * 60_000,
}: Partial<WaitOptions> & { request?: () => Promise<Pick<Response, "ok">> }): Promise<WakeResult> {
  try {
    if (!(await request()).ok) return "failed";
  } catch {
    return "failed";
  }
  return (await waitForBackend({ probe, intervalMs, timeoutMs })) ? "ready" : "timeout";
}
