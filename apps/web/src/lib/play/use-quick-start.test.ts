import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/fetcher";
import { useQuickStart } from "./use-quick-start";

const mocks = vi.hoisted(() => ({
  mutate: vi.fn(),
  push: vi.fn(),
  removeQueries: vi.fn(),
  invalidateQueries: vi.fn(),
  rememberMode: vi.fn(),
  options: {} as { mutation: { onSuccess: (attempt: { id: string }) => void; onError: (error: Error) => void } },
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: mocks.push }) }));
vi.mock("@tanstack/react-query", () => ({ useQueryClient: () => mocks }));
vi.mock("@/lib/play/mode", () => ({ rememberMode: mocks.rememberMode }));
vi.mock("@/lib/api/generated/endpoints", () => ({
  getGetCurrentAttemptQueryKey: (slug: string) => [slug, "current"],
  useStartAttempt: (options: typeof mocks.options) => {
    mocks.options = options;
    return { mutate: mocks.mutate, isPending: false, error: null };
  },
}));

describe("すぐに始める出題条件", () => {
  beforeEach(() => vi.clearAllMocks());
  it("おすすめは5問・登録順・全対象を指定し、中断中の挑戦を破棄しない", () => {
    useQuickStart("demo", "learn", "all", { order: "registered", limit: 5 }).start({ categoryId: "starter" });
    expect(mocks.mutate).toHaveBeenCalledWith({
      slug: "demo",
      data: { categoryId: "starter", scope: "all", order: "registered", limit: 5 },
    });
    mocks.options.mutation.onSuccess({ id: "attempt" });
    expect(mocks.rememberMode).toHaveBeenCalledWith("attempt", "learn");
    expect(mocks.push).toHaveBeenCalledWith("/t/demo/play/session?attempt=attempt&mode=learn");
  });
  it("既存の復習は件数制限なし・ランダムのまま", () => {
    useQuickStart("private", "learn", "review").start();
    expect(mocks.mutate).toHaveBeenCalledWith({ slug: "private", data: { scope: "review", order: "random" } });
  });
  it("409では別タブで始まった中断中の挑戦を再取得する", () => {
    useQuickStart("demo", "learn", "all");
    mocks.options.mutation.onError(new ApiError(409, null));
    expect(mocks.invalidateQueries).toHaveBeenCalledWith({ queryKey: ["demo", "current"] });
    expect(mocks.push).not.toHaveBeenCalled();
  });
});
