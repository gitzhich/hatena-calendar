import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { mapLink } from "./map-link.ts";

/**
 * 会場の地図 URL（FR-09 / ADR-0022）。
 *
 * 会場名には `/` `&` `・` `(` が実際に含まれる。エスケープ漏れは
 * クエリを途中で切る。値は末尾まで書く
 * （docs/coding-guidelines.md「外部へ送る値は末尾まで検証する」）。
 */
describe("mapLink", () => {
  it("会場名が null / 空白のみならリンクを出さない", () => {
    assert.equal(mapLink(null, null), null);
    assert.equal(mapLink(null, "ChIJ123"), null);
    assert.equal(mapLink("", null), null);
    assert.equal(mapLink("   ", null), null);
    assert.equal(mapLink("\u3000", "ChIJ123"), null);
  });

  it("placeId があれば query_place_id が付き confirmed が true", () => {
    const link = mapLink("愛知・大須RADHALL", "ChIJxxxxxxxxxxxxxxxxxxxxxxx");
    assert.deepEqual(link, {
      href: "https://www.google.com/maps/search/?api=1&query=%E6%84%9B%E7%9F%A5%E3%83%BB%E5%A4%A7%E9%A0%88RADHALL&query_place_id=ChIJxxxxxxxxxxxxxxxxxxxxxxx",
      confirmed: true,
    });
  });

  it("placeId が null なら query_place_id が付かず confirmed が false", () => {
    const link = mapLink("愛知・大須RADHALL", null);
    assert.deepEqual(link, {
      href: "https://www.google.com/maps/search/?api=1&query=%E6%84%9B%E7%9F%A5%E3%83%BB%E5%A4%A7%E9%A0%88RADHALL",
      confirmed: false,
    });
  });

  it("& と ・ を含む会場名がクエリを壊さない", () => {
    const name = "愛知・NAGOYA CLUB QUATTRO & RAD HALL";
    const link = mapLink(name, null);
    assert.deepEqual(link, {
      href: "https://www.google.com/maps/search/?api=1&query=%E6%84%9B%E7%9F%A5%E3%83%BBNAGOYA%20CLUB%20QUATTRO%20%26%20RAD%20HALL",
      confirmed: false,
    });
  });

  it("/ と括弧を含む会場名も区切りを壊さない", () => {
    // ADR-0022 の実データ。`(` は encodeURIComponent が残す
    const name = "千葉・草ぶえの丘(千葉 佐倉) / Orange Shelter";
    const link = mapLink(name, null);
    assert.deepEqual(link, {
      href: "https://www.google.com/maps/search/?api=1&query=%E5%8D%83%E8%91%89%E3%83%BB%E8%8D%89%E3%81%B6%E3%81%88%E3%81%AE%E4%B8%98(%E5%8D%83%E8%91%89%20%E4%BD%90%E5%80%89)%20%2F%20Orange%20Shelter",
      confirmed: false,
    });
  });

  it("query_place_id も encodeURIComponent する", () => {
    const link = mapLink("会場", "id/with&special");
    assert.deepEqual(link, {
      href: "https://www.google.com/maps/search/?api=1&query=%E4%BC%9A%E5%A0%B4&query_place_id=id%2Fwith%26special",
      confirmed: true,
    });
  });
});

describe("組み立ては mapLink に置く", () => {
  it("AppearanceCard は Maps URL を直書きしない", () => {
    const source = readFileSync(
      new URL("../components/AppearanceCard.tsx", import.meta.url),
      "utf8",
    );
    assert.match(source, /mapLink/);
    assert.doesNotMatch(source, /google\.com\/maps/);
  });
});
