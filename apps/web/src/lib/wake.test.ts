import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WAKE_REQUESTED_KEY, onWakeStarted, reachedBackend, resumeWake, wakeBackend } from "./wake";

function response(status: number, contentType = "application/json") {
  return { ok: status >= 200 && status < 300, headers: new Headers({ "content-type": contentType }) };
}

function memoryStorage(initial: Record<string, string> = {}) {
  const items = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => items.get(key) ?? null,
    setItem: (key: string, value: string) => void items.set(key, value),
    removeItem: (key: string) => void items.delete(key),
  };
}

const stopped = () => Promise.resolve(response(500));
const reached = () => Promise.resolve(response(200));

describe("reachedBackend", () => {
  it("バックエンドの応答は、エラーでも届いたとみなす", () => {
    expect(reachedBackend(response(200))).toBe(true);
    expect(reachedBackend(response(404, "application/problem+json"))).toBe(true);
    expect(reachedBackend(response(500, "application/problem+json"))).toBe(true);
  });

  it("API Gateway が返した 500 / 503 / 504 は、まだ届いていない", () => {
    expect(reachedBackend(response(500))).toBe(false);
    expect(reachedBackend(response(503))).toBe(false);
    expect(reachedBackend(response(504))).toBe(false);
  });
});

// テストの中では、既定で何も覚えない（Node には localStorage がない環境もある）
const storage = null;

describe("wakeBackend", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("起こしたあと、届くようになったら ready", async () => {
    const probe = vi.fn().mockImplementationOnce(stopped).mockImplementationOnce(stopped).mockImplementation(reached);
    const result = wakeBackend({ storage, request: reached, probe, intervalMs: 10_000, timeoutMs: 60_000 });

    await vi.advanceTimersByTimeAsync(20_000);
    await expect(result).resolves.toBe("ready");
    expect(probe).toHaveBeenCalledTimes(3);
  });

  it("通信に失敗しても、待ち続ける", async () => {
    const probe = vi
      .fn()
      .mockImplementationOnce(() => Promise.reject(new TypeError("Failed to fetch")))
      .mockImplementation(reached);
    const result = wakeBackend({ storage, request: reached, probe, intervalMs: 10_000, timeoutMs: 60_000 });

    await vi.advanceTimersByTimeAsync(10_000);
    await expect(result).resolves.toBe("ready");
  });

  it("時間内に届かなければ timeout", async () => {
    const result = wakeBackend({ storage, request: reached, probe: stopped, intervalMs: 10_000, timeoutMs: 30_000 });

    await vi.advanceTimersByTimeAsync(30_000);
    await expect(result).resolves.toBe("timeout");
  });

  it("起こす口が断ったら（起こす口がない環境、ログインが切れた）、待たずに failed", async () => {
    const probe = vi.fn(reached);
    const result = wakeBackend({ storage, request: () => Promise.resolve(response(404)), probe });

    await expect(result).resolves.toBe("failed");
    expect(probe).not.toHaveBeenCalled();
  });

  it("待っている間に呼ばれても、起こすのは 1 回", async () => {
    const request = vi.fn(reached);
    const first = wakeBackend({ storage, request, probe: reached });
    const second = wakeBackend({ storage, request, probe: reached });

    await expect(Promise.all([first, second])).resolves.toEqual(["ready", "ready"]);
    expect(request).toHaveBeenCalledTimes(1);
  });

  it("使えるようになったら、onReady を 1 回だけ呼ぶ", async () => {
    const onReady = vi.fn();
    const first = wakeBackend({ storage, request: reached, probe: reached, onReady });
    const second = wakeBackend({ storage, request: reached, probe: reached, onReady });

    await Promise.all([first, second]);
    expect(onReady).toHaveBeenCalledTimes(1);
  });

  it("始まったことを、どこから押されても知らせる", async () => {
    const started = vi.fn();
    const unsubscribe = onWakeStarted(started);
    const wake = wakeBackend({ storage, request: reached, probe: reached });

    expect(started).toHaveBeenCalledWith(wake);
    await wake;
    unsubscribe();
  });
});

describe("起動を頼んだ時刻（DEV-211）", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("起こす口が受け付けたら、時刻を覚える。終わったら消す", async () => {
    const memory = memoryStorage();
    const wake = wakeBackend({
      storage: memory,
      request: reached,
      probe: stopped,
      intervalMs: 10_000,
      timeoutMs: 30_000,
    });

    await vi.advanceTimersByTimeAsync(0);
    expect(memory.getItem(WAKE_REQUESTED_KEY)).toBe(String(Date.now()));
    await vi.advanceTimersByTimeAsync(30_000);
    await wake;
    expect(memory.getItem(WAKE_REQUESTED_KEY)).toBeNull();
  });

  it("起こす口が断ったら、覚えない", async () => {
    const memory = memoryStorage();
    const setItem = vi.spyOn(memory, "setItem");

    await wakeBackend({ storage: memory, request: () => Promise.resolve(response(404)), probe: reached });
    expect(setItem).not.toHaveBeenCalled();
  });

  it("頼んでから時間内なら、起こさずに続きを待つ。待つのは頼んだ時刻から数えた残りだけ", async () => {
    const memory = memoryStorage({ [WAKE_REQUESTED_KEY]: String(Date.now() - 4 * 60_000) });
    const request = vi.fn(reached);
    const resumed = resumeWake({ storage: memory, request, probe: stopped, intervalMs: 10_000, timeoutMs: 5 * 60_000 });

    expect(resumed).not.toBeNull();
    await vi.advanceTimersByTimeAsync(60_000);
    await expect(resumed).resolves.toBe("timeout");
    expect(request).not.toHaveBeenCalled();
  });

  it("続きを待って届いたら ready", async () => {
    const memory = memoryStorage({ [WAKE_REQUESTED_KEY]: String(Date.now() - 60_000) });
    const resumed = resumeWake({ storage: memory, probe: reached });

    await expect(resumed).resolves.toBe("ready");
  });

  it("記録がない、時間を過ぎた、読めない値なら、続きを待たない", () => {
    expect(resumeWake({ storage: memoryStorage() })).toBeNull();
    expect(
      resumeWake({ storage: memoryStorage({ [WAKE_REQUESTED_KEY]: String(Date.now() - 5 * 60_000) }) }),
    ).toBeNull();
    expect(resumeWake({ storage: memoryStorage({ [WAKE_REQUESTED_KEY]: "x" }) })).toBeNull();
    expect(resumeWake({ storage: null })).toBeNull();
  });

  it("覚えられない環境でも、起こして待てる", async () => {
    const broken = {
      getItem: () => {
        throw new Error("SecurityError");
      },
      setItem: () => {
        throw new Error("SecurityError");
      },
      removeItem: () => {
        throw new Error("SecurityError");
      },
    };

    await expect(wakeBackend({ storage: broken, request: reached, probe: reached })).resolves.toBe("ready");
    expect(resumeWake({ storage: broken })).toBeNull();
  });
});
