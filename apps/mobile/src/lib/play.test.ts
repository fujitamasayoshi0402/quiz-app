import { describe, expect, it } from "vitest";
import type { PlayableCategoryResponse } from "@/lib/api/generated/model";
import { parseMode, parseModes, rangeOptions, rememberMode, toCriteria } from "./play";

const difficulty = (id: string, level: number, quizCount = 1) => ({ id, level, name: id.toUpperCase(), quizCount });

/** SAA と DVA が同じレベル 2 に並ぶ。CLF はレベル 1 */
const aws: PlayableCategoryResponse = {
  id: "aws",
  name: "AWS",
  quizCount: 6,
  difficulties: [difficulty("clf", 1), difficulty("saa", 2, 2), difficulty("dva", 2, 3)],
};

describe("rangeOptions", () => {
  it("すべて、難易度ごと、難易度が 2 つ以上あるレベルの順に並べる", () => {
    expect(rangeOptions(aws).map(({ value, hint }) => [value, hint])).toEqual([
      ["all", "6 問"],
      ["difficulty:clf", "レベル 1・1 問"],
      ["difficulty:saa", "レベル 2・2 問"],
      ["difficulty:dva", "レベル 2・3 問"],
      ["level:2", "SAA・DVA・5 問"],
    ]);
  });
});

describe("toCriteria", () => {
  it.each([
    [{ kind: "all" } as const, { categoryId: "aws", difficultyId: undefined, level: undefined }],
    [{ kind: "difficulty", id: "saa" } as const, { categoryId: "aws", difficultyId: "saa", level: undefined }],
    [{ kind: "level", level: 2 } as const, { categoryId: "aws", difficultyId: undefined, level: 2 }],
  ])("範囲 %j を出題 API の条件にする", (range, expected) => {
    expect(toCriteria("aws", range)).toEqual(expected);
  });
});

describe("parseMode", () => {
  it.each([
    ["exam", "exam"],
    ["learn", "learn"],
    ["swipe", "learn"],
    [undefined, "learn"],
  ] as const)("%s は %s", (value, expected) => {
    expect(parseMode(value)).toBe(expected);
  });
});

describe("rememberMode", () => {
  it("挑戦ごとに方式を覚える。同じ挑戦は上書きする", () => {
    expect(rememberMode({ a: "learn", b: "exam" }, "a", "exam")).toEqual({ b: "exam", a: "exam" });
  });

  it("20 件を超えたら、古いものから捨てる", () => {
    const modes = Object.fromEntries(Array.from({ length: 20 }, (_, i) => [`a${i}`, "learn" as const]));
    const remembered = rememberMode(modes, "new", "exam");

    expect(Object.keys(remembered)).toHaveLength(20);
    expect(remembered).not.toHaveProperty("a0");
    expect(remembered.new).toBe("exam");
  });
});

describe("parseModes", () => {
  it.each([null, "", "not json", "[]", "1"])("読めないもの（%j）は、空にする", (raw) => {
    expect(parseModes(raw)).toEqual({});
  });

  it("知らない方式は、学習モードにする", () => {
    expect(parseModes('{"a":"exam","b":"swipe"}')).toEqual({ a: "exam", b: "learn" });
  });
});
