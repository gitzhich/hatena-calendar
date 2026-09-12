/**
 * カレンダーチップの色。地域ごとに固定する（FR-10 / ADR-0022）。
 *
 * 色だけ知らない値を UNKNOWN に倒す。API が地方を増やしても塗色は壊れない。
 * ラベル・集計・絞り込みは生の値を保つ。倒すと増えたことに気づけない。
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
  selected: string | null,
): boolean {
  return selected === null || value === selected;
}

/** その月に出演がある地域だけを返す。既知は REGIONS の順、未知はその後ろに辞書順。 */
export function countedRegions(
  values: string[],
): { region: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const value of values) {
    counts.set(value, (counts.get(value) ?? 0) + 1);
  }
  const known = REGIONS.flatMap((region) => {
    const count = counts.get(region) ?? 0;
    return count === 0 ? [] : [{ region, count }];
  });
  const knownSet = new Set<string>(REGIONS);
  const unknown = [...counts.keys()]
    .filter((region) => !knownSet.has(region))
    .sort()
    .map((region) => ({ region, count: counts.get(region) ?? 0 }));
  return [...known, ...unknown];
}
