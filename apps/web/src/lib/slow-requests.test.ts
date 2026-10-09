import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { type SlowRequestState, watchSlowRequests } from "./slow-requests";

/** `ms` 後に `value` で解決する。遅い応答の代わり */
function after<T>(ms: number, value: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms));
}

function failAfter(ms: number): Promise<never> {
  return new Promise((_, reject) => setTimeout(() => reject(new Error("504")), ms));
}

describe("watchSlowRequests", () => {
  let client: QueryClient;
  let states: SlowRequestState[];
  let stop: () => void;

  beforeEach(() => {
    vi.useFakeTimers();
    client = new QueryClient();
    states = [];
    stop = watchSlowRequests(client, (state) => states.push(state));
  });

  afterEach(() => {
    stop();
    client.clear();
    vi.useRealTimers();
  });

  it("3 秒を超えた読み込みで知らせ、終わったら消す", async () => {
    const loading = client.fetchQuery({ queryKey: ["slow"], queryFn: () => after(15_000, "ok") });

    await vi.advanceTimersByTimeAsync(2_999);
    expect(states).toEqual([]);

    await vi.advanceTimersByTimeAsync(1);
    expect(states).toEqual(["waiting"]);

    await vi.advanceTimersByTimeAsync(12_000);
    await loading;
    expect(states).toEqual(["waiting", "none"]);
  });

  it("速い読み込みでは知らせない", async () => {
    await Promise.all([
      client.fetchQuery({ queryKey: ["a"], queryFn: () => after(500, "a") }),
      vi.advanceTimersByTimeAsync(500),
    ]);
    await vi.advanceTimersByTimeAsync(5_000);

    expect(states).toEqual([]);
  });

  // 読み込みが途切れたら数え直す。速い読み込みが続いても、合わせて 3 秒を超えたとは扱わない
  it("速い読み込みが続いても、合わせた時間では知らせない", async () => {
    for (const key of ["a", "b", "c", "d"]) {
      await Promise.all([
        client.fetchQuery({ queryKey: [key], queryFn: () => after(1_000, key) }),
        vi.advanceTimersByTimeAsync(1_000),
      ]);
    }

    expect(states).toEqual([]);
  });

  // 止まっている DB の復帰が API Gateway の待ち時間（30 秒）を超えると 504 が返り、画面が試し直す
  it("失敗して試し直している間は、そのことを知らせる", async () => {
    const queryFn = vi
      .fn()
      .mockImplementationOnce(() => failAfter(30_000))
      .mockImplementationOnce(() => after(5_000, "ok"));
    const loading = client.fetchQuery({ queryKey: ["resume"], queryFn, retry: 1, retryDelay: 1_000 });

    await vi.advanceTimersByTimeAsync(3_000);
    expect(states).toEqual(["waiting"]);

    await vi.advanceTimersByTimeAsync(27_000);
    expect(states).toEqual(["waiting", "retrying"]);

    await vi.advanceTimersByTimeAsync(6_000);
    await loading;
    expect(states).toEqual(["waiting", "retrying", "none"]);
  });

  it("保存も同じように見る", async () => {
    const saving = client
      .getMutationCache()
      .build(client, { mutationFn: () => after(10_000, "saved") })
      .execute(undefined);

    await vi.advanceTimersByTimeAsync(3_000);
    expect(states).toEqual(["waiting"]);

    await vi.advanceTimersByTimeAsync(7_000);
    await saving;
    expect(states).toEqual(["waiting", "none"]);
  });
});
