import { describe, expect, it } from "vitest";
import { calendarWeeks, cellLabel, levelOf } from "./calendar";

describe("calendarWeeks", () => {
  // 2026-10-16 は金曜日
  const today = "2026-10-16";

  it("いちばん右の列が今日の週で、日曜から始まる。今日より後の欄は空ける", () => {
    const weeks = calendarWeeks([], today, 2);

    expect(weeks).toHaveLength(2);
    expect(weeks[0][0]?.date).toBe("2026-10-04");
    expect(weeks[1][0]?.date).toBe("2026-10-11");
    expect(weeks[1][5]).toEqual({ date: today, count: 0, isToday: true });
    expect(weeks[1][6]).toBeNull();
  });

  it("回答のあった日に数を入れる。月をまたいでもずれない", () => {
    const weeks = calendarWeeks(
      [
        { date: "2026-09-30", count: 3 },
        { date: "2026-10-01", count: 1 },
      ],
      today,
      3,
    );

    expect(weeks[0][3]).toEqual({ date: "2026-09-30", count: 3, isToday: false });
    expect(weeks[0][4]).toEqual({ date: "2026-10-01", count: 1, isToday: false });
  });
});

describe("levelOf", () => {
  it("解かなかった日は 0、数が多いほど濃い", () => {
    expect([0, 1, 2, 3, 5, 6, 9, 10, 40].map(levelOf)).toEqual([0, 1, 1, 2, 2, 3, 3, 4, 4]);
  });
});

describe("cellLabel", () => {
  it("日付と数を、色を見なくても分かる文にする", () => {
    expect(cellLabel({ date: "2026-10-16", count: 5, isToday: true })).toBe("10月16日(金)（今日） 5 問");
    expect(cellLabel({ date: "2026-10-15", count: 0, isToday: false })).toBe("10月15日(木) 解いていない");
  });
});
