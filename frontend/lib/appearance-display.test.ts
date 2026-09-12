import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  UNCONFIRMED_TIME_LABEL,
  chipLabel,
  dayCellCountLabel,
  daysInMonth,
  emptyDaySheetCopy,
  firstWeekdayOfMonth,
  formatIsoDate,
  formatIsoDateWithWeekday,
  formatTimeRange,
  performanceLabel,
  weekdayOfIsoDate,
} from "./appearance-display.ts";

describe("appearance-display", () => {
  it("ISO 日付をスラッシュに置き換えるだけ", () => {
    assert.equal(formatIsoDate("2026-09-06"), "2026/09/06");
  });

  it("曜日は JST の暦日として UTC 日付から求める", () => {
    // 2026-09-06 は日曜日
    assert.equal(weekdayOfIsoDate("2026-09-06"), "日");
    assert.equal(weekdayOfIsoDate("2026-09-07"), "月");
    assert.equal(formatIsoDateWithWeekday("2026-09-06"), "2026/09/06（日）");
  });

  it("月またぎ・年またぎ・うるう日の曜日", () => {
    assert.equal(weekdayOfIsoDate("2026-09-30"), "水");
    assert.equal(weekdayOfIsoDate("2026-10-01"), "木");
    assert.equal(weekdayOfIsoDate("2026-12-31"), "木");
    assert.equal(weekdayOfIsoDate("2027-01-01"), "金");
    assert.equal(weekdayOfIsoDate("2028-02-29"), "火");
  });

  it("月の日数と 1 日の曜日（2 月・うるう年・12 月）", () => {
    assert.equal(daysInMonth(2026, 2), 28);
    assert.equal(daysInMonth(2028, 2), 29);
    assert.equal(daysInMonth(2026, 12), 31);
    assert.equal(daysInMonth(2027, 1), 31);
    assert.equal(firstWeekdayOfMonth(2026, 2), 0);
    assert.equal(firstWeekdayOfMonth(2028, 2), 2);
    assert.equal(firstWeekdayOfMonth(2026, 12), 2);
    assert.equal(firstWeekdayOfMonth(2027, 1), 5);
  });

  it("取得失敗時は「予定はありません」「出演なし」と出さない", () => {
    assert.equal(dayCellCountLabel(false, 0), "取得できませんでした");
    assert.doesNotMatch(dayCellCountLabel(false, 0), /出演なし/);
    assert.equal(emptyDaySheetCopy(false, false), "出演情報を取得できませんでした。");
    assert.doesNotMatch(emptyDaySheetCopy(false, false) ?? "", /予定はありません/);
    assert.equal(dayCellCountLabel(true, 0), "出演なし");
    assert.equal(dayCellCountLabel(true, 2), "出演 2 件");
    assert.equal(emptyDaySheetCopy(true, false), "この日の出演予定はありません。");
    assert.equal(emptyDaySheetCopy(true, true), null);
  });

  it("絞り込みで空になった日は「予定はありません」と出さない", () => {
    assert.equal(
      emptyDaySheetCopy(true, true, true),
      "この日の出演は、地域の絞り込みで非表示になっています。",
    );
    assert.doesNotMatch(emptyDaySheetCopy(true, true, true) ?? "", /予定はありません/);
    assert.equal(emptyDaySheetCopy(false, true, true), "出演情報を取得できませんでした。");
  });

  it("開始が無ければ null。終了が無ければ開始だけ", () => {
    assert.equal(formatTimeRange(null, "21:00:00"), null);
    assert.equal(formatTimeRange("19:30:00", null), "19:30");
    assert.equal(formatTimeRange("19:30:00", "20:00:00"), "19:30–20:00");
  });

  it("チップと出演欄は時刻が無ければ「時刻未定」", () => {
    assert.equal(chipLabel(null), UNCONFIRMED_TIME_LABEL);
    assert.equal(chipLabel("14:30:00"), "14:30");
    assert.equal(performanceLabel(null, null), UNCONFIRMED_TIME_LABEL);
    assert.equal(performanceLabel("14:30:00", "14:55:00"), "14:30–14:55");
  });
});
