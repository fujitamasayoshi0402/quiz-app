import { describe, expect, it } from "vitest";
import { moveItem } from "./reorder";

describe("上下のボタンでの並べ替え", () => {
  const items = ["a", "b", "c"];

  it("隣と入れ替えた新しい並びを返し、元の並びは変えない", () => {
    expect(moveItem(items, 1, -1)).toEqual(["b", "a", "c"]);
    expect(moveItem(items, 1, 1)).toEqual(["a", "c", "b"]);
    expect(items).toEqual(["a", "b", "c"]);
  });

  it("端にあって動かせなければ undefined", () => {
    expect(moveItem(items, 0, -1)).toBeUndefined();
    expect(moveItem(items, 2, 1)).toBeUndefined();
    expect(moveItem(items, 5, -1)).toBeUndefined();
  });

  it("組を指定すると、同じ組の隣とだけ入れ替える。難易度は同じレベルの中だけで動かす", () => {
    const difficulties = [
      { name: "CLF", level: 1 },
      { name: "SAA", level: 2 },
      { name: "DVA", level: 2 },
    ];
    const sameLevel = (a: { level: number }, b: { level: number }) => a.level === b.level;

    expect(moveItem(difficulties, 1, -1, sameLevel)).toBeUndefined();
    expect(moveItem(difficulties, 1, 1, sameLevel)?.map((d) => d.name)).toEqual(["CLF", "DVA", "SAA"]);
  });
});
