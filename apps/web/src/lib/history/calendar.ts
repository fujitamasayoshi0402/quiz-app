import type { StudyDayCount } from "@/lib/api/generated/model";

/**
 * 学習の記録のカレンダー（DEV-179）。列が週、行が曜日（日〜土）で、いちばん右の列が今日の週。
 *
 * 日付はサーバーが日本時間で決めた `YYYY-MM-DD`。時刻を持たない日付として、UTC の 0 時で計算する。
 * 端末のタイムゾーンで読むと、日付が前後にずれる。
 */
export type CalendarCell = { date: string; count: number; isToday: boolean };

const DAY_MS = 24 * 60 * 60 * 1000;

const toMs = (date: string) => Date.parse(`${date}T00:00:00Z`);
const toDate = (ms: number) => new Date(ms).toISOString().slice(0, 10);

/** [weeks] 列のカレンダー。今日より後の欄は null */
export function calendarWeeks(days: readonly StudyDayCount[], today: string, weeks: number): (CalendarCell | null)[][] {
  const counts = new Map(days.map((day) => [day.date, day.count]));
  const todayMs = toMs(today);
  const start = todayMs - (new Date(todayMs).getUTCDay() + (weeks - 1) * 7) * DAY_MS;

  return Array.from({ length: weeks }, (_, week) =>
    Array.from({ length: 7 }, (_, weekday) => {
      const ms = start + (week * 7 + weekday) * DAY_MS;
      if (ms > todayMs) return null;
      const date = toDate(ms);
      return { date, count: counts.get(date) ?? 0, isToday: ms === todayMs };
    }),
  );
}

/** 濃さの段階（0〜4）。0 は解かなかった日 */
export function levelOf(count: number): 0 | 1 | 2 | 3 | 4 {
  if (count === 0) return 0;
  if (count <= 2) return 1;
  if (count <= 5) return 2;
  if (count <= 9) return 3;
  return 4;
}

const dateFormat = new Intl.DateTimeFormat("ja-JP", {
  month: "long",
  day: "numeric",
  weekday: "short",
  timeZone: "UTC",
});

/** 読み上げと title に使う。色だけに頼らない */
export function cellLabel(cell: CalendarCell): string {
  const date = dateFormat.format(new Date(toMs(cell.date)));
  return `${date}${cell.isToday ? "（今日）" : ""} ${cell.count === 0 ? "解いていない" : `${cell.count} 問`}`;
}
