import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { DemoStarterCard, findDemoStarter } from "./demo-starter-card";

const quickStart = vi.hoisted(() => ({ start: vi.fn(), isPending: false, conflict: false, error: null }));
vi.mock("@/lib/play/use-quick-start", () => ({ useQuickStart: () => quickStart }));
const categories = [{ id: "starter", name: "まずは5問", quizCount: 5 }];
const render = (slug = "demo", items = categories) =>
  renderToStaticMarkup(<DemoStarterCard slug={slug} categories={items} />);

describe("デモのおすすめ5問", () => {
  beforeEach(() => {
    quickStart.isPending = false;
    quickStart.conflict = false;
  });
  it("デモに5問そろったときだけ入口を出す", () => {
    expect(render()).toContain("おすすめの5問を始める");
    expect(render("private")).toBe("");
    expect(render("demo", [])).toBe("");
    expect(render("demo", [{ ...categories[0], quizCount: 4 }])).toBe("");
    expect(render("demo", [{ ...categories[0], quizCount: 6 }])).toBe("");
  });
  it("開始待ちではボタンを無効にする", () => {
    quickStart.isPending = true;
    expect(render()).toContain('disabled=""');
    expect(render()).toContain("準備しています…");
  });
  it("中断中の挑戦があれば再開か明示的な破棄を案内する", () => {
    quickStart.conflict = true;
    expect(render()).toContain("下の「再開する」か「破棄する」");
  });
});

describe("一覧から外す「まずは5問」（DEV-237）", () => {
  const snacks = { id: "snacks", name: "お菓子・飲み物", quizCount: 12 };
  it("入口を出すときは、そのカテゴリを返す", () => {
    expect(findDemoStarter("demo", [snacks, ...categories])).toBe(categories[0]);
  });
  it("入口を出さないときは返さない。一覧に残り、選べるまま", () => {
    expect(findDemoStarter("private", categories)).toBeUndefined();
    expect(findDemoStarter("demo", [snacks])).toBeUndefined();
    expect(findDemoStarter("demo", [{ ...categories[0], quizCount: 4 }])).toBeUndefined();
  });
});
