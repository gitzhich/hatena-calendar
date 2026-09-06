/** 暦日（YYYY-MM-DD、JST）の表示用。ロケール API は使わない。 */
export const WEEKDAYS = ["日", "月", "火", "水", "木", "金", "土"] as const;

export function weekdayOfIsoDate(iso: string): (typeof WEEKDAYS)[number] {
  const [year, month, day] = iso.split("-").map(Number);
  const weekday = new Date(Date.UTC(year, month - 1, day)).getUTCDay();
  return WEEKDAYS[weekday];
}

export function formatIsoDate(iso: string): string {
  return iso.replaceAll("-", "/");
}

export function formatIsoDateWithWeekday(iso: string): string {
  return `${formatIsoDate(iso)}（${weekdayOfIsoDate(iso)}）`;
}

/** タイムテーブル未確定の出演に出す文言。チップと詳細カードで同じにする。 */
export const UNCONFIRMED_TIME_LABEL = "時刻未定";

/** 開始が無ければ null。物販など、欄ごと出さない項目用（FR-04）。 */
export function formatTimeRange(start: string | null, end: string | null): string | null {
  if (start === null) return null;
  return `${start.slice(0, 5)}${end ? `–${end.slice(0, 5)}` : ""}`;
}

/** 詳細カードの出演欄。時刻が無ければ「時刻未定」（FR-04）。 */
export function performanceLabel(start: string | null, end: string | null): string {
  return formatTimeRange(start, end) ?? UNCONFIRMED_TIME_LABEL;
}

/** カレンダーチップ用。時刻が無い出演は「時刻未定」。 */
export function chipLabel(performanceStartTime: string | null): string {
  return performanceStartTime === null
    ? UNCONFIRMED_TIME_LABEL
    : performanceStartTime.slice(0, 5);
}
