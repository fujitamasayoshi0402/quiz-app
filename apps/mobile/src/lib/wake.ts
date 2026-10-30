/**
 * 止まっている dev のバックエンドを起こし、使えるようになるまで待つ（DEV-209。web の `lib/wake.ts` と同じ考え方）。
 *
 * `POST /api/wake` は API Gateway が受け、バックエンドを通らずに起こす（`infra/terraform/modules/quiz-service/wake.tf`）。
 * 起きるまで 1.5〜2 分かかるため、届くようになるまで間をおいて確かめる。
 *
 * 画面に止まっている旨の案内がいくつ並んでも、起こして待つのは 1 回にする。
 * web と違い、頼んだことは端末に覚えない。アプリを開き直すことは、読み込み直しやタブほど起きない。React と端末には触れない
 */

/** 起こして待った結果 */
export type WakeResult = "ready" | "failed" | "timeout";

/** 届いたかを確かめる要求。軽く、ログインしていれば誰でも読める（所属の一覧。最初の画面が読む） */
export const PROBE_PATH = "/api/me/tenants";

/**
 * バックエンドまで届いた応答か。
 *
 * バックエンドの応答は、エラーも ProblemDetail で返る。手前の API Gateway が返す応答（送り先がない 500 / 503、
 * 時間切れの 504）は `{"message": ...}` の形で、まだ起動の途中にある
 */
export function reachedBackend(response: Pick<Response, "ok" | "headers">): boolean {
  return response.ok || (response.headers.get("content-type")?.startsWith("application/problem+json") ?? false);
}

type WakeOptions = {
  /** 起こす要求（`POST /api/wake`） */
  request: () => Promise<Pick<Response, "ok">>;
  /** 届いたかを確かめる要求（`PROBE_PATH`） */
  probe: () => Promise<Pick<Response, "ok" | "headers">>;
  intervalMs?: number;
  timeoutMs?: number;
  /** 使えるようになったときに 1 回だけ呼ぶ。画面に案内がいくつ並んでも、読み込み直すのは 1 回にする */
  onReady?: () => unknown;
};

let inflight: Promise<WakeResult> | null = null;
const startListeners = new Set<(wake: Promise<WakeResult>) => void>();

/** 起こして待つのが始まったら知らせる。並んだ案内のどれから押されても、すべてに起動中を出せるように */
export function onWakeStarted(listener: (wake: Promise<WakeResult>) => void): () => void {
  startListeners.add(listener);
  return () => startListeners.delete(listener);
}

/** 起こして待っている途中なら、その結果。あとから描いた案内（画面を移った先）も、起動中を出せるように */
export function currentWake(): Promise<WakeResult> | null {
  return inflight;
}

/**
 * 起こして、使えるようになるまで待つ。待っている間に呼ばれたら、同じ結果を返す。
 *
 * 起こす口がない環境（prod）では 404 になり、`failed` を返す。画面は prod ではボタンを出さない
 */
export function wakeBackend(options: WakeOptions): Promise<WakeResult> {
  if (inflight) return inflight;
  const wake = run(options).finally(() => {
    inflight = null;
  });
  inflight = wake;
  for (const listener of startListeners) listener(wake);
  return wake;
}

async function run({ request, probe, intervalMs = 10_000, timeoutMs = 5 * 60_000, onReady }: WakeOptions) {
  try {
    if (!(await request()).ok) return "failed";
  } catch {
    return "failed";
  }
  if (!(await waitForBackend(probe, intervalMs, timeoutMs))) return "timeout";
  await onReady?.();
  return "ready";
}

/** バックエンドに届くまで、間をおいて確かめる。`timeoutMs` を過ぎたら諦める */
async function waitForBackend(probe: WakeOptions["probe"], intervalMs: number, timeoutMs: number): Promise<boolean> {
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
