import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { todayInJst } from "./calendar-range.ts";
import { toIsoDate } from "./iso-date.ts";

describe("toIsoDate", () => {
  it("1 桁の月日をゼロ埋めする", () => {
    assert.equal(toIsoDate({ year: 2026, month: 9, day: 7 }), "2026-09-07");
  });

  it("2 桁の月日はそのまま繋ぐ", () => {
    assert.equal(toIsoDate({ year: 2026, month: 12, day: 31 }), "2026-12-31");
  });

  it("JST と UTC で日付が変わる時刻では JST の暦日になる", () => {
    // 2026-09-06T15:30:00Z = 2026-09-07 00:30 JST
    assert.equal(
      toIsoDate(todayInJst(new Date("2026-09-06T15:30:00Z"))),
      "2026-09-07",
    );
    // 2026-09-06T14:59:00Z = 2026-09-06 23:59 JST
    assert.equal(
      toIsoDate(todayInJst(new Date("2026-09-06T14:59:00Z"))),
      "2026-09-06",
    );
  });
});
