"use client";

import { regionLabel } from "@/lib/region";
import { regionClass } from "@/lib/region-color";

/**
 * 凡例と絞り込みを分ける（FR-10 / ADR-0022）。
 *
 * 同じ行の先頭に押せる `<select>`（44px）、続きに押せない小さなピル（約 22px）。
 * 凡例を押せるようにすると NFR-06 の 44px が戻り、幅 360px でカレンダー本体の
 * 半分を占める。
 *
 * 地域が 1 種類の月は select を出さない。絞る意味が無く、44px だけ取るため。
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
    <div className="mb-4 flex w-full min-w-0 flex-wrap items-center gap-2">
      {counts.length > 1 && (
        <select
          aria-label="地域で絞り込み"
          value={selected ?? ""}
          onChange={(event) =>
            onSelect(event.target.value === "" ? null : event.target.value)
          }
          className="min-h-11 max-w-full shrink-0 rounded-card border border-line bg-surface px-2 text-sm text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
        >
          <option value="">すべての地域</option>
          {counts.map(({ region, count }) => (
            <option key={region} value={region}>
              {regionLabel(region)} {count}
            </option>
          ))}
        </select>
      )}
      {counts.map(({ region, count }) => (
        <span
          key={region}
          className={`rounded-chip px-1.5 text-[10px] leading-5 font-bold whitespace-nowrap ${regionClass(region)}`}
        >
          {regionLabel(region)}{" "}
          <span className="tabular-nums">{count}</span>
        </span>
      ))}
    </div>
  );
}
