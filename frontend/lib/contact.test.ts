import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { CONTACT_EMAIL, CONTACT_REQUEST_ITEMS } from "./contact.ts";

/**
 * 削除要請の窓口（LR-05 / ADR-0017）。
 *
 * **窓口が無い、あるいは古いまま、という状態で公開されるのを止める。**
 * LR-05 はリリース判定基準に入っており、掲載漏れは要件違反になる。
 */
describe("問い合わせ窓口", () => {
  const read = (path: string) =>
    readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

  it("アドレスが埋まっている", () => {
    assert.notEqual(
      CONTACT_EMAIL,
      "__UNSET__",
      "lib/contact.ts の CONTACT_EMAIL にサイト専用のアドレスを入れる（ADR-0017）",
    );
    assert.match(CONTACT_EMAIL, /^[^\s@]+@[^\s@]+\.[^\s@]+$/);
  });

  it("要請時に書いてほしいことを示している", () => {
    assert.ok(CONTACT_REQUEST_ITEMS.length > 0);
  });

  /**
   * **ソースを読んで確かめる。** アドレスを画面に直接書かれると、
   * 変更したときに片方だけ古いまま残る。実際に起きうる形なので規約を検査する
   * （docs/coding-guidelines.md 第 11 章）。
   */
  describe("掲載側は定数を参照する", () => {
    const PAGES = ["components/Disclaimer.tsx", "app/unavailable/page.tsx"];

    for (const path of PAGES) {
      it(`${path} がアドレスをリテラルで持たない`, () => {
        const source = read(path);
        assert.doesNotMatch(
          source,
          /["'][^\s"'@]+@[^\s"'@]+\.[^\s"'@]+["']/,
          "アドレスは lib/contact.ts の CONTACT_EMAIL を使う",
        );
        assert.match(source, /CONTACT_EMAIL/);
      });
    }
  });

  /**
   * LR-01（全ページに非公式である旨の記載）と LR-05（連絡手段の記載）を
   * 同じ場所で満たす。**ページごとに置くと、新しいページで忘れる。**
   * 実際に Disclaimer が CalendarPage の中にしか無く、
   * /unavailable には出ていなかった。
   */
  it("Disclaimer をルートレイアウトが出す。ページごとに置かない", () => {
    assert.match(read("app/layout.tsx"), /<Disclaimer\s*\/>/);
    assert.doesNotMatch(read("components/CalendarPage.tsx"), /<Disclaimer\s*\/>/);
  });
});
