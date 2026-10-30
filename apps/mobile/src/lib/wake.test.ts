import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentWake, onWakeStarted, reachedBackend, wakeBackend } from "./wake";

function response(status: number, contentType = "application/json") {
  return { ok: status >= 200 && status < 300, headers: new Headers({ "content-type": contentType }) };
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

describe("wakeBackend", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("起こしたあと、届くようになったら ready。使えるようになったことを 1 回だけ知らせる", async () => {
    const probe = vi.fn().mockImplementationOnce(stopped).mockImplementationOnce(stopped).mockImplementation(reached);
    const onReady = vi.fn();
    const result = wakeBackend({ request: reached, probe, onReady, intervalMs: 10_000, timeoutMs: 60_000 });

    await vi.advanceTimersByTimeAsync(20_000);
    await expect(result).resolves.toBe("ready");
    expect(probe).toHaveBeenCalledTimes(3);
    expect(onReady).toHaveBeenCalledTimes(1);
  });

  it("通信に失敗しても、待ち続ける", async () => {
    const probe = vi
      .fn()
      .mockImplementationOnce(() => Promise.reject(new TypeError("Network request failed")))
      .mockImplementation(reached);
    const result = wakeBackend({ request: reached, probe, intervalMs: 10_000, timeoutMs: 60_000 });

    await vi.advanceTimersByTimeAsync(10_000);
    await expect(result).resolves.toBe("ready");
  });

  it("届かないまま時間を過ぎたら timeout", async () => {
    const onReady = vi.fn();
    const result = wakeBackend({ request: reached, probe: stopped, onReady, intervalMs: 10_000, timeoutMs: 30_000 });

    await vi.advanceTimersByTimeAsync(30_000);
    await expect(result).resolves.toBe("timeout");
    expect(onReady).not.toHaveBeenCalled();
  });

  it("起こす口がない（404）か、呼べなければ failed。確かめには行かない", async () => {
    const probe = vi.fn(reached);
    await expect(wakeBackend({ request: () => Promise.resolve(response(404)), probe })).resolves.toBe("failed");
    await expect(wakeBackend({ request: () => Promise.reject(new TypeError("offline")), probe })).resolves.toBe(
      "failed",
    );
    expect(probe).not.toHaveBeenCalled();
  });

  it("待っている間に押されても、起こすのは 1 回で、同じ結果を返す", async () => {
    const request = vi.fn(reached);
    const first = wakeBackend({ request, probe: stopped, intervalMs: 10_000, timeoutMs: 30_000 });
    const second = wakeBackend({ request, probe: reached });

    expect(second).toBe(first);
    expect(currentWake()).toBe(first);
    await vi.advanceTimersByTimeAsync(30_000);
    await first;
    expect(request).toHaveBeenCalledTimes(1);
    expect(currentWake()).toBeNull();
  });

  it("始まったことを、並んだ案内に知らせる", async () => {
    const listener = vi.fn();
    const unsubscribe = onWakeStarted(listener);
    const wake = wakeBackend({ request: reached, probe: reached });
    unsubscribe();

    expect(listener).toHaveBeenCalledWith(wake);
    await wake;
  });
});
