/**
 * 次の復習の日を、今日からの近さで言う（DEV-178）。日付はサーバーが日本時間で決めた `YYYY-MM-DD`。
 *
 * 日付だけを持つため、時刻のずれで前の日にならないよう、日本時間の正午として読む。
 */
const dateFormat = new Intl.DateTimeFormat("ja-JP", {
  month: "long",
  day: "numeric",
  weekday: "short",
  timeZone: "Asia/Tokyo",
});

const DAY_MS = 24 * 60 * 60 * 1000;

const noonInJapan = (date: string) => new Date(`${date}T12:00:00+09:00`);

export function nextReviewLabel(nextDueOn: string, today: string): string {
  const days = Math.round((noonInJapan(nextDueOn).getTime() - noonInJapan(today).getTime()) / DAY_MS);
  const date = dateFormat.format(noonInJapan(nextDueOn));
  return days === 1 ? `明日（${date}）` : date;
}
