/**
 * 公開カレンダーが受け付ける年月の範囲（FR-05 / ADR-0014）。
 *
 * バックエンドの CalendarRange と同じ方針をフロント側でも持つ。
 * ここで弾くことに意味があるのは、**取得の前に落とせばキャッシュミスが
 * DB へ到達しない**ため（docs/security.md T-04）。
 * バックエンド側の検証は多層防御として残す。
 */
export const SERVICE_START = { year: 2026, month: 1 } as const;
export const MAX_FUTURE_MONTHS = 24;

/** イベントの暦日は JST で判定する（NFR-05）。 */
export const JST_OFFSET_MINUTES = 9 * 60;

/** JST における「今日」を年月日で返す。サーバの TZ 設定に依存させない。 */
export function todayInJst(now: Date = new Date()): {
  year: number;
  month: number;
  day: number;
} {
  const jst = new Date(now.getTime() + JST_OFFSET_MINUTES * 60_000);
  return {
    year: jst.getUTCFullYear(),
    month: jst.getUTCMonth() + 1,
    day: jst.getUTCDate(),
  };
}

const asIndex = (year: number, month: number) => year * 12 + (month - 1);

export function isWithinRange(year: number, month: number, now?: Date): boolean {
  if (!Number.isInteger(year) || !Number.isInteger(month)) return false;
  if (month < 1 || month > 12) return false;

  const today = todayInJst(now);
  const lower = asIndex(SERVICE_START.year, SERVICE_START.month);
  const upper = asIndex(today.year, today.month) + MAX_FUTURE_MONTHS;
  const target = asIndex(year, month);
  return target >= lower && target <= upper;
}

/** "2026" / "09" のような URL セグメントを検証して数値にする。 */
export function parseMonthParams(
  yearParam: string,
  monthParam: string,
): { year: number; month: number } | null {
  if (!/^\d{4}$/.test(yearParam) || !/^\d{1,2}$/.test(monthParam)) return null;
  const year = Number(yearParam);
  const month = Number(monthParam);
  if (!isWithinRange(year, month)) return null;
  return { year, month };
}

/** その月の初日と末日を YYYY-MM-DD で返す。 */
export function monthBounds(year: number, month: number): { from: string; to: string } {
  const pad = (n: number) => String(n).padStart(2, "0");
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return {
    from: `${year}-${pad(month)}-01`,
    to: `${year}-${pad(month)}-${pad(lastDay)}`,
  };
}
