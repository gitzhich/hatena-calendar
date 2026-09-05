import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { formatJst } from "./last-updated.ts";

/**
 * 日時の表示方法（NFR-05 / docs/data-model.md「タイムゾーンの扱い」）。
 *
 * **ソースを読んで確かめる。** 守りたいのは「実行環境のタイムゾーン設定と ICU データに
 * 依存する API で日時を出さない」という規約そのもので、振る舞いのテストでは捕まらない。
 * ICU が同梱されない構成では `timeZone` の指定が黙って無視され、**例外にならずに
 * 9 時間ずれた時刻がそのまま表示される**（`lib/last-updated.ts`）。
 *
 * 数値の桁区切り（`resources.toLocaleString("ja-JP")` など）はタイムゾーンに
 * 関係しないため対象外。禁じるのは日時をロケール依存で組み立てることに限る。
 */
describe("日時の表示", () => {
  /** 使ってはいけない書き方。日時をロケールとタイムゾーンの設定に委ねるもの。 */
  const FORBIDDEN = [
    /\btoLocaleDateString\b/,
    /\btoLocaleTimeString\b/,
    /\bIntl\.DateTimeFormat\b/,
    /\btimeZone\s*:/,
    /new Date\([^)]*\)\s*\.\s*toLocale/,
  ];

  /** このファイル自身は禁止パターンを文字列として持つため除く。 */
  const SELF = "date-display.test.ts";

  function sources(dir: string): string[] {
    const found: string[] = [];
    for (const name of readdirSync(new URL(`../${dir}/`, import.meta.url))) {
      const path = `${dir}/${name}`;
      const url = new URL(`../${path}`, import.meta.url);
      if (statSync(url).isDirectory()) {
        found.push(...sources(path));
      } else if (/\.tsx?$/.test(name) && name !== SELF) {
        found.push(path);
      }
    }
    return found;
  }

  it("app / components / lib のどこにもロケール依存の日時整形が無い", () => {
    const offenders: string[] = [];
    for (const path of [...sources("app"), ...sources("components"), ...sources("lib")]) {
      const source = readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
      for (const pattern of FORBIDDEN) {
        if (pattern.test(source)) offenders.push(`${path} (${pattern.source})`);
      }
    }
    assert.deepEqual(
      offenders,
      [],
      "日時は formatJst で組み立てる。ICU 非同梱の環境で 9 時間ずれても例外にならない",
    );
  });

  it("formatJst は UTC の入力を JST に直す", () => {
    // 2026-08-30T12:00:00Z は JST では同日 21:00
    assert.equal(formatJst("2026-08-30T12:00:00Z"), "2026/08/30 21:00");
  });

  it("日付が変わる時刻でも繰り上がる", () => {
    // JST の +9 時間で翌日へ入る境界
    assert.equal(formatJst("2026-08-30T15:00:00Z"), "2026/08/31 00:00");
    assert.equal(formatJst("2026-08-30T14:59:00Z"), "2026/08/30 23:59");
  });
});
