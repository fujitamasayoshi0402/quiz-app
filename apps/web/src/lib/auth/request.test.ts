import { describe, expect, it } from "vitest";
import { safeReturnTo } from "./request";

describe("safeReturnTo", () => {
  it("アプリの中のパスは、クエリとともにそのまま返す", () => {
    expect(safeReturnTo("/t/demo/play?category=1")).toBe("/t/demo/play?category=1");
    expect(safeReturnTo("/invitations/abc")).toBe("/invitations/abc");
  });

  it("無いときと、パスでないときはトップに戻す", () => {
    expect(safeReturnTo(undefined)).toBe("/");
    expect(safeReturnTo(null)).toBe("/");
    expect(safeReturnTo("")).toBe("/");
    expect(safeReturnTo("https://evil.example/")).toBe("/");
    expect(safeReturnTo("javascript:alert(1)")).toBe("/");
  });

  it("外部のオリジンに解けるものは通さない", () => {
    // どれもブラウザの URL の解釈では、外部のオリジンになる
    for (const value of [
      "//evil.example",
      "/\\evil.example",
      "/\t/evil.example",
      "/\n/evil.example",
      "/\r\\evil.example",
    ]) {
      expect(safeReturnTo(value)).toBe("/");
    }
  });
});
