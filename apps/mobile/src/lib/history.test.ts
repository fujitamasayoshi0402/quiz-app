import { describe, expect, it } from "vitest";
import type { AttemptHistoryItem } from "@/lib/api/generated/model";
import { attemptRate, attemptTitle, rateOf, scopeLabel } from "./history";

const item: AttemptHistoryItem = {
  id: "a",
  categoryId: "c",
  categoryName: "世界の地理",
  difficultyId: null,
  difficultyName: null,
  level: null,
  scope: "all",
  totalCount: 10,
  answeredCount: 8,
  correctCount: 7,
  startedAt: "2026-10-17T00:00:00Z",
  finishedAt: "2026-10-17T00:10:00Z",
};

describe("rateOf", () => {
  it("回答が 5 問に満たなければ、率を出さない", () => {
    expect(rateOf(4, 0)).toBeNull();
  });

  it("5 問からは、答えた問題のうち正解した割合を出す", () => {
    expect(rateOf(6, 4)).toBe(67);
  });
});

describe("attemptRate", () => {
  it("出題数を分母にする。解かずに終えた問題は不正解と同じ", () => {
    expect(attemptRate(item)).toBe(70);
  });

  it("出題が 0 問なら 0%", () => {
    expect(attemptRate({ totalCount: 0, correctCount: 0 })).toBe(0);
  });
});

describe("attemptTitle", () => {
  it.each([
    ["範囲がすべて", {}, "世界の地理・すべての難易度"],
    ["難易度を選んだ", { difficultyId: "d", difficultyName: "かんたん" }, "世界の地理・かんたん"],
    ["レベルを選んだ", { level: 2 }, "世界の地理・レベル 2 のすべて"],
    ["カテゴリを選んでいない", { categoryId: null, categoryName: null }, "すべてのカテゴリ・すべての難易度"],
    [
      "いま出題されていない",
      { categoryName: null, difficultyId: "d", difficultyName: null },
      "出題されていないカテゴリ・出題されていない難易度",
    ],
  ])("%s", (_, override, expected) => {
    expect(attemptTitle({ ...item, ...override })).toBe(expected);
  });
});

describe("scopeLabel", () => {
  it("web で選べる出題対象も、名前で出す", () => {
    expect(scopeLabel("incorrect")).toBe("間違えた問題");
  });

  it("知らないものは、そのまま出す", () => {
    expect(scopeLabel("future")).toBe("future");
  });
});
