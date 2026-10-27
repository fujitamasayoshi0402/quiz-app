/**
 * 止まっているバックエンドを起こし、使えるようになるまで待つ（DEV-154）。
 *
 * dev のバックエンドは、使われなくなると止まる。`POST /api/wake` は API Gateway が受け、バックエンドを通らずに起こす
 * （`infra/terraform/modules/quiz-service/wake.tf`）。起きるまで 2〜3 分かかるため、届くようになるまで間をおいて確かめる。
 *
 * 画面に止まっている旨の案内がいくつ並んでも、起こして待つのは 1 回にする。
 * 頼んだ時刻はブラウザに覚え、読み込み直したときや別のタブでは、起こさずに続きを待つ（DEV-211）。
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

/** 起動を頼んだ時刻（エポックミリ秒）を置くキー。読み込み直したときや別のタブでも、起動中と分かるようにする（DEV-211） */
export const WAKE_REQUESTED_KEY = "wake-requested-at";

type WakeStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

type WakeOptions = Partial<WaitOptions> & {
  request?: () => Promise<Pick<Response, "ok">>;
  storage?: WakeStorage | null;
  /** 使えるようになったときに 1 回だけ呼ぶ。画面に案内がいくつ並んでも、読み込み直すのは 1 回にする */
  onReady?: () => unknown;
};

const DEFAULT_TIMEOUT_MS = 5 * 60_000;

let inflight: Promise<WakeResult> | null = null;
const startListeners = new Set<(wake: Promise<WakeResult>) => void>();

/** 起こして待つのが始まったら知らせる。同じ画面に並んだ案内が、どれから押されても起動中を出せるように */
export function onWakeStarted(listener: (wake: Promise<WakeResult>) => void): () => void {
  startListeners.add(listener);
  return () => startListeners.delete(listener);
}

function start(run: () => Promise<WakeResult>, storage: WakeStorage | null): Promise<WakeResult> {
  if (inflight) return inflight;
  const wake = run().finally(() => {
    inflight = null;
    forget(storage);
  });
  inflight = wake;
  for (const listener of startListeners) listener(wake);
  return wake;
}

/**
 * 起こして、使えるようになるまで待つ。待っている間に呼ばれたら、同じ結果を返す。
 *
 * 起こす口がない環境（ローカル、prod）では、`/api/wake` がバックエンドに届かないか 404 になり、`failed` を返す
 */
export function wakeBackend(options: WakeOptions = {}): Promise<WakeResult> {
  const storage = options.storage === undefined ? browserStorage() : options.storage;
  return start(() => run(options, storage), storage);
}

/**
 * 前に頼んだ起動がまだ続いていれば、起こさずに続きを待つ。続いていなければ null。
 *
 * 読み込み直す前や別のタブで頼んだものは、頼んだ時刻から数えて待つ。時刻を過ぎていれば、起動はもう済んだか、失敗している
 */
export function resumeWake(options: WakeOptions = {}): Promise<WakeResult> | null {
  if (inflight) return inflight;
  const storage = options.storage === undefined ? browserStorage() : options.storage;
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const remainingMs = remainingOf(storage, timeoutMs);
  if (remainingMs <= 0) return null;
  return start(() => wait({ ...options, timeoutMs: remainingMs }), storage);
}

async function run(options: WakeOptions, storage: WakeStorage | null): Promise<WakeResult> {
  const { request = () => fetch("/api/wake", { method: "POST" }) } = options;
  try {
    if (!(await request()).ok) return "failed";
  } catch {
    return "failed";
  }
  remember(storage);
  return wait(options);
}

async function wait({
  probe = () => fetch(PROBE_PATH),
  intervalMs = 10_000,
  timeoutMs = DEFAULT_TIMEOUT_MS,
  onReady,
}: WakeOptions): Promise<WakeResult> {
  if (!(await waitForBackend({ probe, intervalMs, timeoutMs }))) return "timeout";
  await onReady?.();
  return "ready";
}

/** 頼んでから待つ残りの時間。記録がなければ 0 */
function remainingOf(storage: WakeStorage | null, timeoutMs: number): number {
  let requestedAt: number;
  try {
    requestedAt = Number(storage?.getItem(WAKE_REQUESTED_KEY) ?? Number.NaN);
  } catch {
    return 0;
  }
  if (!Number.isFinite(requestedAt)) return 0;
  // 時計が戻ったなどで先の時刻になっていても、待つのは timeoutMs まで
  return Math.min(requestedAt + timeoutMs - Date.now(), timeoutMs);
}

function remember(storage: WakeStorage | null) {
  try {
    storage?.setItem(WAKE_REQUESTED_KEY, String(Date.now()));
  } catch {
    // 覚えられない環境（プライベートウィンドウ、サイトのデータを止めている）では、読み込み直すとまた押せるだけなので、無視してよい
  }
}

function forget(storage: WakeStorage | null) {
  try {
    storage?.removeItem(WAKE_REQUESTED_KEY);
  } catch {
    // remember と同じ
  }
}

function browserStorage(): WakeStorage | null {
  try {
    return typeof localStorage === "undefined" ? null : localStorage;
  } catch {
    return null;
  }
}
