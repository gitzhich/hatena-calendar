"use client";

import { regionLabel } from "@/lib/region";
import { regionClass } from "@/lib/region-color";

/**
 * 凡例のピルをそのまま絞り込みのトグルにする（FR-10 / NFR-06）。
 *
 * 塗りは 20px のまま、button の透明な余白で 44×44 を確保する。
 * 選択中は塗りに outline を出し、色や opacity では状態を伝えない（NFR-08）。
 * 「すべて」は足さない。幅 360px で 6 個目が折り返すため、選択中をもう一度押して解除する。
 * 0 件の月は行ごと出さない。横スクロールにはしない。
 */
export function RegionBar({
  counts,
  selected,
  onSelect,
}: {
  counts: { region: string; count: number }[];
  selected: string | null;
  onSelect: (region: string | null) => void;
}) {
  if (counts.length === 0) return null;

  return (
    <div
      role="group"
      aria-label="地域で絞り込み"
      className="mb-4 flex w-full min-w-0 flex-wrap items-center gap-2"
    >
      {counts.map(({ region, count }) => {
        const pressed = selected === region;
        return (
          <button
            key={region}
            type="button"
            aria-pressed={pressed}
            onClick={() => onSelect(pressed ? null : region)}
            className="inline-flex min-h-11 min-w-11 items-center justify-center p-0 cursor-pointer focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
          >
            <span
              className={`rounded-chip px-1.5 text-[10px] leading-5 font-bold whitespace-nowrap ${regionClass(region)}${
                pressed ? " outline outline-2 outline-offset-1 outline-ink" : ""
              }`}
            >
              {regionLabel(region)}{" "}
              <span className="tabular-nums">{count}</span>
            </span>
          </button>
        );
      })}
    </div>
  );
}
