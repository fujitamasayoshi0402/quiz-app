"use client";

import { ApiErrorAlert } from "@/components/api-error-alert";
import { Skeleton } from "@/components/ui/skeleton";
import { useGetStudyDays } from "@/lib/api/generated/endpoints";
import { type CalendarCell, calendarWeeks, cellLabel, levelOf } from "@/lib/history/calendar";
import { cn } from "@/lib/utils";

/** サーバーが返す日数（12 週と 1 日）に合わせる */
const WEEKS = 12;

/** 濃さ。前景の色を重ねるため、明るい画面でも暗い画面でも段階が見分けられる */
const LEVELS = ["bg-muted", "bg-primary/20", "bg-primary/40", "bg-primary/70", "bg-primary"];

/**
 * 学習の記録（DEV-179）。続けて解いた日の数と、直近 12 週の解いた日のカレンダー。
 *
 * 日の区切りは日本時間（サーバーが決める）。色だけに頼らず、欄ごとに日付と数を読み上げと title で出す。
 */
export function StudyCalendar({ slug }: { slug: string }) {
  const study = useGetStudyDays(slug);

  if (study.isPending) return <Skeleton className="h-40 w-full" />;
  if (study.isError) return <ApiErrorAlert error={study.error} />;

  const { today, streak, days } = study.data;
  const weeks = calendarWeeks(days, today, WEEKS);
  const solvedToday = days.some((day) => day.date === today);
  const total = days.reduce((sum, day) => sum + day.count, 0);

  return (
    <div className="space-y-4 rounded-lg border p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <p className="flex items-baseline gap-1">
          <span className="text-sm text-muted-foreground">続けて解いた日</span>
          <span className="ml-1 text-2xl font-semibold tabular-nums">{streak}</span>
          <span className="text-sm">日</span>
        </p>
        <p className="text-xs text-muted-foreground">
          {streak > 0 && !solvedToday ? "今日はまだ解いていません。" : ""}直近 {WEEKS} 週で {total} 問
        </p>
      </div>

      <div className="flex gap-1" role="group" aria-label={`直近 ${WEEKS} 週の、解いた日`}>
        {weeks.map((week, index) => (
          <div key={index} className="flex flex-col gap-1">
            {week.map((cell, weekday) =>
              cell ? <Cell key={cell.date} cell={cell} /> : <span key={weekday} className="size-4" />,
            )}
          </div>
        ))}
      </div>

      <div className="flex items-center gap-1 text-xs text-muted-foreground" aria-hidden>
        <span className="mr-1">少ない</span>
        {LEVELS.map((level) => (
          <span key={level} className={cn("size-3 rounded-sm", level)} />
        ))}
        <span className="ml-1">多い</span>
      </div>
    </div>
  );
}

function Cell({ cell }: { cell: CalendarCell }) {
  const label = cellLabel(cell);
  return (
    <span
      role="img"
      aria-label={label}
      title={label}
      className={cn("size-4 rounded-sm", LEVELS[levelOf(cell.count)], cell.isToday && "ring-2 ring-ring ring-offset-1")}
    />
  );
}
