import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  DEFAULT_APPEARANCE_SORT,
  adminAppearancesHref,
  parseAppearanceSort,
} from "./admin-appearance-query.ts";

describe("parseAppearanceSort", () => {
  it("既定と既知の値はそのまま返す", () => {
    assert.equal(parseAppearanceSort("DATE_DESC"), "DATE_DESC");
    assert.equal(parseAppearanceSort("DATE_ASC"), "DATE_ASC");
    assert.equal(parseAppearanceSort("CREATED_DESC"), "CREATED_DESC");
    assert.equal(parseAppearanceSort("CREATED_ASC"), "CREATED_ASC");
  });

  it("無い・空・知らない値は既定に倒す", () => {
    assert.equal(parseAppearanceSort(undefined), DEFAULT_APPEARANCE_SORT);
    assert.equal(parseAppearanceSort(""), DEFAULT_APPEARANCE_SORT);
    assert.equal(parseAppearanceSort("nope"), DEFAULT_APPEARANCE_SORT);
    assert.equal(parseAppearanceSort("date_desc"), DEFAULT_APPEARANCE_SORT);
  });
});

describe("adminAppearancesHref", () => {
  it("既定の並び順は URL に sort を付けない", () => {
    assert.equal(adminAppearancesHref({ sort: "DATE_DESC" }), "/admin");
    assert.equal(
      adminAppearancesHref({ sourceType: "AUTO", sort: "DATE_DESC" }),
      "/admin?sourceType=AUTO",
    );
  });

  it("既定以外の並び順は sort を付ける", () => {
    assert.equal(
      adminAppearancesHref({ sort: "CREATED_DESC" }),
      "/admin?sort=CREATED_DESC",
    );
  });

  it("絞り込みを切り替えても並び順を保ち、page は付けない", () => {
    assert.equal(
      adminAppearancesHref({ sourceType: "MANUAL", sort: "DATE_ASC" }),
      "/admin?sourceType=MANUAL&sort=DATE_ASC",
    );
  });

  it("ページャは sourceType と sort を引き継ぐ", () => {
    assert.equal(
      adminAppearancesHref({
        sourceType: "AUTO",
        sort: "CREATED_ASC",
        page: 2,
      }),
      "/admin?sourceType=AUTO&sort=CREATED_ASC&page=2",
    );
  });

  it("既定の並びでページだけ送るときは sort を付けない", () => {
    assert.equal(
      adminAppearancesHref({ sort: "DATE_DESC", page: 1 }),
      "/admin?page=1",
    );
  });
});
