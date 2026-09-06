import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  UNCONFIRMED_TIME_LABEL,
  chipLabel,
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
