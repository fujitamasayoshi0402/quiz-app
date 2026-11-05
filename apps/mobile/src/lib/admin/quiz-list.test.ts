import { describe, expect, it } from "vitest";
import {
  ALL,
  NO_FILTERS,
  accuracyLabel,
  activeFilterCount,
  canManage,
  sortByAccuracy,
  statusLabel,
  toSearchParams,
} from "./quiz-list";

const quiz = (id: string, answerCount: number, correctCount: number) => ({ id, answerCount, correctCount });

describe("toSearchParams", () => {
  it("「すべて」は条件にしない", () => {
    expect(toSearchParams(NO_FILTERS)).toEqual({
      categoryId: undefined,
      difficultyId: undefined,
      status: undefined,
      aiGenerated: undefined,
    });
  });

  it("選んだものを条件にする", () => {
    expect(toSearchParams({ categoryId: "c", difficultyId: "d", status: "draft", origin: "ai" })).toEqual({
      categoryId: "c",
      difficultyId: "d",
      status: "draft",
      aiGenerated: true,
    });
  });

  it("人が作ったものは aiGenerated を false にする", () => {
    expect(toSearchParams({ ...NO_FILTERS, origin: "human" }).aiGenerated).toBe(false);
  });
});

describe("activeFilterCount", () => {
  it("「すべて」でない条件を数える", () => {
    expect(activeFilterCount(NO_FILTERS)).toBe(0);
    expect(activeFilterCount({ categoryId: "c", difficultyId: ALL, status: "draft", origin: ALL })).toBe(2);
  });
});

describe("accuracyLabel", () => {
  it("回答が少なければ率を出さない", () => {
    expect(accuracyLabel(quiz("a", 4, 0))).toBe("回答が少ない");
  });

  it("回答が足りれば率を出す", () => {
    expect(accuracyLabel(quiz("a", 10, 7))).toBe("正答率 70%");
  });
});

describe("sortByAccuracy", () => {
  it("低い順に並べ、率のないものは最後に置く", () => {
    const sorted = sortByAccuracy([quiz("few", 1, 0), quiz("high", 10, 9), quiz("low", 10, 2)]);
    expect(sorted.map((q) => q.id)).toEqual(["low", "high", "few"]);
  });

  it("率が同じなら元の並びのまま", () => {
    const sorted = sortByAccuracy([quiz("a", 10, 5), quiz("b", 10, 5), quiz("c", 2, 0), quiz("d", 3, 0)]);
    expect(sorted.map((q) => q.id)).toEqual(["a", "b", "c", "d"]);
  });

  it("元の配列を変えない", () => {
    const original = [quiz("high", 10, 9), quiz("low", 10, 2)];
    sortByAccuracy(original);
    expect(original.map((q) => q.id)).toEqual(["high", "low"]);
  });
});

describe("statusLabel", () => {
  it("公開と下書きを分ける", () => {
    expect(statusLabel("published")).toBe("公開");
    expect(statusLabel("draft")).toBe("下書き");
  });
});

describe("canManage", () => {
  it("管理者にだけ入口を出す", () => {
    expect(canManage("admin")).toBe(true);
    expect(canManage("member")).toBe(false);
    expect(canManage("viewer")).toBe(false);
    expect(canManage(undefined)).toBe(false);
  });
});
