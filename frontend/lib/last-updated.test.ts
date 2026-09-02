import { test } from "node:test";
import assert from "node:assert/strict";
import { formatJst } from "./last-updated.ts";

test("UTC を JST に変換する（+9 時間）", () => {
  assert.equal(formatJst("2026-09-02T05:30:00Z"), "2026/09/02 14:30");
});

test("UTC の 15:00 は JST の翌日 00:00。日付が繰り上がる", () => {
  assert.equal(formatJst("2026-09-02T15:00:00Z"), "2026/09/03 00:00");
});

test("月をまたぐ繰り上がり", () => {
  assert.equal(formatJst("2026-08-31T15:00:00Z"), "2026/09/01 00:00");
});

test("年をまたぐ繰り上がり", () => {
  assert.equal(formatJst("2026-12-31T15:00:00Z"), "2027/01/01 00:00");
});

test("UTC の 14:59 はまだ当日", () => {
  assert.equal(formatJst("2026-09-02T14:59:00Z"), "2026/09/02 23:59");
});

test("オフセット付きの表記でも同じ瞬間なら同じ結果", () => {
  // バックエンドは UTC で返す契約だが、絶対時刻として解釈していることを確かめる。
  // ここで表記に引きずられていると、契約が変わった瞬間に 9 時間ずれる
  assert.equal(formatJst("2026-09-02T14:30:00+09:00"), "2026/09/02 14:30");
});

test("月日と時分を 2 桁に揃える", () => {
  assert.equal(formatJst("2026-01-05T00:05:00Z"), "2026/01/05 09:05");
});

test("マイクロ秒まで付いた値を受け取れる", () => {
  // バックエンドは TIMESTAMPTZ をそのまま載せるため、実際には
  // "2026-09-02T08:25:45.468906Z" のように 6 桁の小数秒が付く。
  // ミリ秒を超える桁で壊れないこと
  assert.equal(formatJst("2026-09-02T08:25:45.468906Z"), "2026/09/02 17:25");
});

test("秒は切り捨てる。分までを表示する", () => {
  assert.equal(formatJst("2026-09-02T05:30:59.999Z"), "2026/09/02 14:30");
});

test("パースできない値は null", () => {
  assert.equal(formatJst("not-a-date"), null);
  assert.equal(formatJst(""), null);
});
