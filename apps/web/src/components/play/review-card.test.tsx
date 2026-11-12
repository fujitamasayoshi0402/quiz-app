import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ReviewCard } from "./review-card";

const summary = vi.hoisted(() => ({ data: undefined as unknown }));
vi.mock("@/lib/api/generated/endpoints", () => ({ useGetReviewSummary: () => summary }));
const quickStart = vi.hoisted(() => ({ start: vi.fn(), isPending: false, conflict: false, error: null }));
vi.mock("@/lib/play/use-quick-start", () => ({ useQuickStart: () => quickStart }));

const SHARED_NOTE = "ほかの人が解いた問題も入ります";

describe("今日の復習", () => {
  beforeEach(() => {
    summary.data = { dueCount: 3, nextDueOn: null, today: "2026-11-12" };
  });

  it("件数が取れないうちは何も出さない", () => {
    summary.data = undefined;
    expect(renderToStaticMarkup(<ReviewCard slug="demo" shared />)).toBe("");
  });

  it("共有のアカウントでは、ほかの人の回答も入ると添える（DEV-236）", () => {
    expect(renderToStaticMarkup(<ReviewCard slug="demo" shared />)).toContain(SHARED_NOTE);
    summary.data = { dueCount: 0, nextDueOn: null, today: "2026-11-12" };
    expect(renderToStaticMarkup(<ReviewCard slug="demo" shared />)).toContain(SHARED_NOTE);
  });

  it("自分のテナントでは添えない", () => {
    const html = renderToStaticMarkup(<ReviewCard slug="mine" />);
    expect(html).toContain("今日の復習 3 問");
    expect(html).not.toContain(SHARED_NOTE);
  });
});
