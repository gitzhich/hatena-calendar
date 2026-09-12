/**
 * カレンダーチップの色。地域ごとに固定する（FR-10 / ADR-0022）。
 *
 * 知らない値は UNKNOWN に倒す。API が地方を増やしても画面は壊れない。
 * 識別子そのものは regionLabel が見せる。
 */

import { REGIONS, type Region } from "./region.ts";

export const REGION_CLASSES: Record<Region, string> = {
  HOKKAIDO: "bg-region-hokkaido text-region-hokkaido-ink",
  TOHOKU: "bg-region-tohoku text-region-tohoku-ink",
  KANTO: "bg-region-kanto text-region-kanto-ink",
  CHUBU: "bg-region-chubu text-region-chubu-ink",
  KINKI: "bg-region-kinki text-region-kinki-ink",
  CHUGOKU: "bg-region-chugoku text-region-chugoku-ink",
  SHIKOKU: "bg-region-shikoku text-region-shikoku-ink",
  KYUSHU: "bg-region-kyushu text-region-kyushu-ink",
  OVERSEAS: "bg-region-overseas text-region-overseas-ink",
  UNKNOWN: "bg-region-unknown text-region-unknown-ink",
};

export function asRegion(value: string): Region {
  return (REGIONS as readonly string[]).includes(value)
    ? (value as Region)
    : "UNKNOWN";
}

export function regionClass(value: string): string {
  return REGION_CLASSES[asRegion(value)];
}

export function matchesRegion(
  value: string,
  selected: Region | null,
): boolean {
  return selected === null || asRegion(value) === selected;
}

/** その月に出演がある地域だけを、REGIONS の順で返す。 */
export function countedRegions(
  values: string[],
): { region: Region; count: number }[] {
  const counts = new Map<Region, number>();
  for (const value of values) {
    const region = asRegion(value);
    counts.set(region, (counts.get(region) ?? 0) + 1);
  }
  return REGIONS.flatMap((region) => {
    const count = counts.get(region) ?? 0;
    return count === 0 ? [] : [{ region, count }];
  });
}
