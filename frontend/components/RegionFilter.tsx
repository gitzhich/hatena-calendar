"use client";

import type { ReactNode } from "react";
import { regionLabel } from "@/lib/region";
import { regionClass } from "@/lib/region-color";

/**
 * 凡例と絞り込みを兼ねる（FR-10 / ADR-0022）。
 *
 * その月に出演がある地域だけを、REGIONS の順で件数付きに並べる。
 * 未知の地域はその後ろに辞書順で置く。
 * 選択状態は色ではなく太い枠線と aria-pressed で示す（NFR-08）。
 */
export function RegionFilter({
  counts,
  total,
  selected,
  onSelect,
}: {
  counts: { region: string; count: number }[];
  total: number;
  selected: string | null;
  onSelect: (region: string | null) => void;
}) {
  if (counts.length === 0) return null;

  return (
    <div
      role="group"
      aria-label="地域で絞り込み"
      className="mb-4 flex w-full min-w-0 flex-wrap gap-2"
    >
      <Toggle
        pressed={selected === null}
        className="bg-surface text-ink"
        onClick={() => onSelect(null)}
      >
        すべて{" "}
        <span className="tabular-nums">{total}</span>
      </Toggle>
      {counts.map(({ region, count }) => (
        <Toggle
          key={region}
          pressed={selected === region}
          className={regionClass(region)}
          onClick={() => onSelect(selected === region ? null : region)}
        >
          {regionLabel(region)}{" "}
          <span className="tabular-nums">{count}</span>
        </Toggle>
      ))}
    </div>
  );
}

function Toggle({
  pressed,
  className,
  onClick,
  children,
}: {
  pressed: boolean;
  className: string;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onClick}
      className={`min-h-11 px-3 rounded-chip text-sm font-bold whitespace-nowrap cursor-pointer border-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent ${
        pressed ? "border-ink" : "border-transparent"
      } ${className}`}
    >
      {children}
    </button>
  );
}
