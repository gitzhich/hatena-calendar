/**
 * 年月日を <input type="date"> が受け付ける YYYY-MM-DD にする。
 *
 * 暦日の判定は todayInJst（calendar-range.ts）が担う。ここは桁を揃えるだけ。
 */
export function toIsoDate({
  year,
  month,
  day,
}: {
  year: number;
  month: number;
  day: number;
}): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${year}-${pad(month)}-${pad(day)}`;
}
