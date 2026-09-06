import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { CHIP_CLASSES, CHIP_COUNT, chipClass, chipIndex } from "./chip-color.ts";

describe("chipIndex", () => {
  it("同じイベント名は常に同じインデックス", () => {
    assert.equal(chipIndex("生誕オフ会"), chipIndex("生誕オフ会"));
  });

  it("範囲は 0 以上 CHIP_COUNT 未満", () => {
    const names = ["愛知", "東京", "千葉", "", "🐼 生誕", "A".repeat(80)];
    for (const name of names) {
      const index = chipIndex(name);
      assert.ok(index >= 0 && index < CHIP_COUNT, `${name} → ${index}`);
    }
  });

  it("chipClass は CHIP_CLASSES の要素を返す", () => {
    const cls = chipClass("NAGOYA Reny Limited");
    assert.equal(CHIP_CLASSES.includes(cls as (typeof CHIP_CLASSES)[number]), true);
  });

  it("CHIP_COUNT は CHIP_CLASSES の長さそのもの", () => {
    assert.equal(CHIP_COUNT, CHIP_CLASSES.length);
  });
});
