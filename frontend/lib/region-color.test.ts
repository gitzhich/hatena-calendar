import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { REGIONS } from "./region.ts";
import {
  REGION_CLASSES,
  asRegion,
  countedRegions,
  matchesRegion,
  regionClass,
} from "./region-color.ts";

describe("regionClass", () => {
  it("既知の地域は REGION_CLASSES の対応する値", () => {
    for (const region of REGIONS) {
      assert.equal(regionClass(region), REGION_CLASSES[region]);
    }
  });

  it("知らない値は unknown に倒す", () => {
    assert.equal(asRegion("OKINAWA"), "UNKNOWN");
    assert.equal(asRegion(""), "UNKNOWN");
    assert.equal(regionClass("OKINAWA"), REGION_CLASSES.UNKNOWN);
    assert.equal(regionClass(""), REGION_CLASSES.UNKNOWN);
  });

  it("UNKNOWN はそのまま UNKNOWN", () => {
    assert.equal(asRegion("UNKNOWN"), "UNKNOWN");
    assert.equal(regionClass("UNKNOWN"), REGION_CLASSES.UNKNOWN);
  });
});

describe("matchesRegion", () => {
  it("未選択ならすべて一致する", () => {
    assert.equal(matchesRegion("KANTO", null), true);
    assert.equal(matchesRegion("OKINAWA", null), true);
  });

  it("知らない値は生の値どうしで一致し、UNKNOWN には倒さない", () => {
    assert.equal(matchesRegion("OKINAWA", "UNKNOWN"), false);
    assert.equal(matchesRegion("OKINAWA", "OKINAWA"), true);
    assert.equal(matchesRegion("OKINAWA", "KANTO"), false);
  });
});

describe("countedRegions", () => {
  it("知らない値は UNKNOWN に合算せず、REGIONS のあとに辞書順で置く", () => {
    assert.deepEqual(countedRegions(["KINKI", "OKINAWA", "KANTO", "KANTO"]), [
      { region: "KANTO", count: 2 },
      { region: "KINKI", count: 1 },
      { region: "OKINAWA", count: 1 },
    ]);
    assert.deepEqual(
      countedRegions(["TOKAI", "OKINAWA", "UNKNOWN", "KANTO"]),
      [
        { region: "KANTO", count: 1 },
        { region: "UNKNOWN", count: 1 },
        { region: "OKINAWA", count: 1 },
        { region: "TOKAI", count: 1 },
      ],
    );
  });
});
