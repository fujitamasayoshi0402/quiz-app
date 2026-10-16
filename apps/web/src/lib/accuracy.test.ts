import { describe, expect, it } from "vitest";
import { MIN_ANSWERS, rateOf } from "./accuracy";

describe("rateOf", () => {
  it("回答が少ないうちは率を出さない", () => {
    expect(rateOf(0, 0)).toBeNull();
    expect(rateOf(MIN_ANSWERS - 1, 0)).toBeNull();
  });

  it("回答が揃えば、整数に丸めた率を出す", () => {
    expect(rateOf(MIN_ANSWERS, 0)).toBe(0);
    expect(rateOf(6, 4)).toBe(67);
    expect(rateOf(8, 8)).toBe(100);
  });
});
