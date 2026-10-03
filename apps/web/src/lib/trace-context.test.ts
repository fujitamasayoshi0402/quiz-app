import { describe, expect, it } from "vitest";
import { newTraceParent } from "./trace-context";

describe("newTraceParent", () => {
  it("W3C の traceparent の形で、記録する印を付ける", () => {
    expect(newTraceParent()).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
  });

  it("trace ID の先頭 8 桁は、作った時刻（エポック秒）", () => {
    const now = Date.parse("2026-10-04T00:00:00Z");
    const traceId = newTraceParent(now).split("-")[1];
    expect(parseInt(traceId.slice(0, 8), 16)).toBe(now / 1000);
  });

  it("要求ごとに違う値になる", () => {
    const values = new Set(Array.from({ length: 100 }, () => newTraceParent()));
    expect(values.size).toBe(100);
  });
});
