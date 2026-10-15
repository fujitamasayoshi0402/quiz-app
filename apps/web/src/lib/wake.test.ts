import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { reachedBackend, wakeBackend } from "./wake";

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

  it("起こしたあと、届くようになったら ready", async () => {
    const probe = vi.fn().mockImplementationOnce(stopped).mockImplementationOnce(stopped).mockImplementation(reached);
    const result = wakeBackend({ request: reached, probe, intervalMs: 10_000, timeoutMs: 60_000 });

    await vi.advanceTimersByTimeAsync(20_000);
    await expect(result).resolves.toBe("ready");
    expect(probe).toHaveBeenCalledTimes(3);
  });

  it("通信に失敗しても、待ち続ける", async () => {
    const probe = vi
      .fn()
      .mockImplementationOnce(() => Promise.reject(new TypeError("Failed to fetch")))
      .mockImplementation(reached);
    const result = wakeBackend({ request: reached, probe, intervalMs: 10_000, timeoutMs: 60_000 });

    await vi.advanceTimersByTimeAsync(10_000);
    await expect(result).resolves.toBe("ready");
  });

  it("時間内に届かなければ timeout", async () => {
    const result = wakeBackend({ request: reached, probe: stopped, intervalMs: 10_000, timeoutMs: 30_000 });

    await vi.advanceTimersByTimeAsync(30_000);
    await expect(result).resolves.toBe("timeout");
  });

  it("起こす口が断ったら（起こす口がない環境、ログインが切れた）、待たずに failed", async () => {
    const probe = vi.fn(reached);
    const result = wakeBackend({ request: () => Promise.resolve(response(404)), probe });

    await expect(result).resolves.toBe("failed");
    expect(probe).not.toHaveBeenCalled();
  });

  it("待っている間に呼ばれても、起こすのは 1 回", async () => {
    const request = vi.fn(reached);
    const first = wakeBackend({ request, probe: reached });
    const second = wakeBackend({ request, probe: reached });

    await expect(Promise.all([first, second])).resolves.toEqual(["ready", "ready"]);
    expect(request).toHaveBeenCalledTimes(1);
  });
});
