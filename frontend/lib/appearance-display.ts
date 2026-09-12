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

/** その月 1 日の曜日（0 = 日）。グリッドの先頭空きマス数。 */
export function firstWeekdayOfMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month - 1, 1)).getUTCDay();
}

/** その月の日数。`month` は 1–12。 */
export function daysInMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
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

const FETCH_FAILED_CELL = "取得できませんでした";
const FETCH_FAILED_SHEET = "出演情報を取得できませんでした。";
const EMPTY_DAY_SHEET = "この日の出演予定はありません。";
const HIDDEN_BY_FILTER_SHEET = "この日の出演は、地域の絞り込みで非表示になっています。";

/**
 * 日付セルの件数ラベル。失敗時に「出演なし」と出さない
 * （空配列は「予定がない」ではなく「取れなかった」）。
 */
export function dayCellCountLabel(fetched: boolean, count: number): string {
  if (!fetched) return FETCH_FAILED_CELL;
  return count > 0 ? `出演 ${count} 件` : "出演なし";
}

/**
 * 日別シートの空表示。失敗時は「予定はありません」を使わない。
 * 予定がある日は null（カード側を出す）。
 * 絞り込みですべて隠れたときは、無いと言わず隠していると出す。
 */
export function emptyDaySheetCopy(
  fetched: boolean,
  hasItems: boolean,
  hiddenByFilter = false,
): string | null {
  if (!fetched) return FETCH_FAILED_SHEET;
  if (!hasItems) return EMPTY_DAY_SHEET;
  if (hiddenByFilter) return HIDDEN_BY_FILTER_SHEET;
  return null;
}
