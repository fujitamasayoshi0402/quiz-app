import { afterEach, describe, expect, it, vi } from "vitest";
import { createDemoWarmUp, WARM_UP_INTERVAL_MS } from "./demo-warm-up";

function setUp() {
  let now = 1_000_000;
  const fetch = vi.fn<(input: string | URL, init?: RequestInit) => Promise<Response>>(
    async () => new Response(null, { status: 204 }),
  );
  vi.stubGlobal("fetch", fetch);
  const warmUp = createDemoWarmUp(
    () => "https://api.example",
    () => now,
  );
  return { fetch, warmUp, advance: (ms: number) => (now += ms) };
}

describe("createDemoWarmUp", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("API の起こす口を、トークンを付けずに呼ぶ", async () => {
    const { fetch, warmUp } = setUp();

    expect(await warmUp()).toBe(true);

    expect(fetch).toHaveBeenCalledOnce();
    const [url, init] = fetch.mock.calls[0];
    expect(url.toString()).toBe("https://api.example/api/warm-up");
    expect(init?.method).toBe("POST");
    // 認証すると、上限を確かめる前に DB が起きる
    expect(new Headers(init?.headers).has("authorization")).toBe(false);
  });

  it("間隔の中では呼ばない。過ぎたらまた呼ぶ", async () => {
    const { fetch, warmUp, advance } = setUp();

    await warmUp();
    advance(WARM_UP_INTERVAL_MS - 1);
    expect(await warmUp()).toBe(false);
    advance(1);
    expect(await warmUp()).toBe(true);

    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("同時に頼まれても、呼ぶのは 1 回", async () => {
    const { fetch, warmUp } = setUp();

    await Promise.all([warmUp(), warmUp(), warmUp()]);

    expect(fetch).toHaveBeenCalledOnce();
  });

  it("API が失敗しても、届かなくても、例外を投げない", async () => {
    const { fetch, warmUp, advance } = setUp();
    vi.spyOn(console, "warn").mockImplementation(() => undefined);

    fetch.mockResolvedValueOnce(new Response(null, { status: 503 }));
    await expect(warmUp()).resolves.toBe(true);

    advance(WARM_UP_INTERVAL_MS);
    fetch.mockRejectedValueOnce(new TypeError("fetch failed"));
    await expect(warmUp()).resolves.toBe(true);
  });
});
