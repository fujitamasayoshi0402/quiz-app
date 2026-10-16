import { describe, expect, it } from "vitest";
import { nextReviewLabel } from "./review";

describe("nextReviewLabel", () => {
  it("明日なら、そう添える", () => {
    expect(nextReviewLabel("2026-10-17", "2026-10-16")).toBe("明日（10月17日(土)）");
  });

  it("それより先なら、日付だけ。月をまたいでもずれない", () => {
    expect(nextReviewLabel("2026-11-01", "2026-10-16")).toBe("11月1日(日)");
  });
});
