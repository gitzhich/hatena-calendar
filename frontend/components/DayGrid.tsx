"use client";

import { createContext, useContext, useEffect, useId, useRef, useState } from "react";
import Link from "next/link";
import type { ReactNode } from "react";
import {
  WEEKDAYS,
  chipLabel,
  dayCellCountLabel,
  daysInMonth as monthLength,
  emptyDaySheetCopy,
  firstWeekdayOfMonth,
  formatIsoDateWithWeekday,
} from "@/lib/appearance-display";
import { chipClass } from "@/lib/chip-color";

const MAX_VISIBLE_CHIPS = 2;

type Today = { year: number; month: number; day: number };

type ChipItem = {
  id: number;
  eventName: string;
  performanceStartTime: string | null;
};

type DayGridProps = {
  year: number;
  month: number;
  chipsByDate: Record<string, ChipItem[]>;
  appearancesOk: boolean;
  today: Today;
  prev: { year: number; month: number } | null;
  next: { year: number; month: number } | null;
  children: ReactNode;
};

const SelectedIsoContext = createContext<string | null>(null);

/** 選択中の日の詳細だけを出す。カード本体は Server Component のまま children で渡す。 */
export function DayPanel({ iso, children }: { iso: string; children: ReactNode }) {
  const selectedIso = useContext(SelectedIsoContext);
  if (iso !== selectedIso) return null;
  return children;
}

/** 日付グリッドと日別シート（FR-01 / FR-02 / FR-03）。ページ全体は Server Component のまま。 */
export function DayGrid({
  year,
  month,
  chipsByDate,
  appearancesOk,
  today,
  prev,
  next,
  children,
}: DayGridProps) {
  const [selectedIso, setSelectedIso] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const closingRef = useRef(false);
  const closeCleanupRef = useRef<(() => void) | null>(null);
  const titleId = useId();
  const pad = (n: number) => String(n).padStart(2, "0");
  const isCurrentMonth = today.year === year && today.month === month;
  const todayDay = isCurrentMonth ? today.day : null;
  const todayIso = `${today.year}-${pad(today.month)}-${pad(today.day)}`;
  const firstWeekday = firstWeekdayOfMonth(year, month);
  const daysInMonth = monthLength(year, month);
  const cells: (number | null)[] = [
    ...Array<null>(firstWeekday).fill(null),
    ...Array.from({ length: daysInMonth }, (_, i) => i + 1),
  ];
  while (cells.length % 7 !== 0) cells.push(null);

  const cancelPendingClose = () => {
    closeCleanupRef.current?.();
    closeCleanupRef.current = null;
  };

  const openDay = (iso: string) => {
    cancelPendingClose();
    closingRef.current = false;
    setSelectedIso(iso);
  };

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (selectedIso !== null && !closingRef.current) {
      if (!dialog.open) dialog.showModal();
    }
  }, [selectedIso]);

  useEffect(() => {
    return () => {
      closeCleanupRef.current?.();
    };
  }, []);

  const finishClose = () => {
    cancelPendingClose();
    setSelectedIso(null);
    closingRef.current = false;
  };

  const handleClose = () => {
    closingRef.current = true;
    const dialog = dialogRef.current;
    const reduced =
      typeof window !== "undefined" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduced || !dialog) {
      finishClose();
      return;
    }
    const onEnd = (event: TransitionEvent) => {
      if (event.target !== dialog) return;
      if (event.propertyName !== "translate") return;
      finishClose();
    };
    dialog.addEventListener("transitionend", onEnd);
    const timer = window.setTimeout(finishClose, 300);
    closeCleanupRef.current = () => {
      dialog.removeEventListener("transitionend", onEnd);
      window.clearTimeout(timer);
    };
  };

  const emptyCopy = emptyDaySheetCopy(
    appearancesOk,
    selectedIso !== null && (chipsByDate[selectedIso] ?? []).length > 0,
  );

  return (
    <SelectedIsoContext.Provider value={selectedIso}>
      <nav className="flex items-center gap-2 mb-4">
        <TodayControl
          isCurrentMonth={isCurrentMonth}
          onOpenToday={() => openDay(todayIso)}
        />
        <h2 id="calendar-heading" className="flex-1 text-center text-lg font-bold tabular-nums min-w-0 truncate">
          {year}年{month}月
        </h2>
        <div className="flex items-center shrink-0">
          <MonthLink target={prev} label="前の月" glyph="←" />
          <MonthLink target={next} label="次の月" glyph="→" />
        </div>
      </nav>

      <div className="grid grid-cols-7 gap-1">
        {WEEKDAYS.map((w) => (
          <div
            key={w}
            className={`text-center text-xs py-2 font-normal ${
              w === "日" ? "text-holiday" : w === "土" ? "text-saturday" : "text-muted"
            }`}
          >
            {w}
          </div>
        ))}

        {cells.map((day, i) => {
          if (day === null) {
            return (
              <div
                key={`empty-${i}`}
                className="min-h-11"
                aria-hidden="true"
              />
            );
          }
          const iso = `${year}-${pad(month)}-${pad(day)}`;
          const items = chipsByDate[iso] ?? [];
          return (
            <DayCell
              key={iso}
              day={day}
              iso={iso}
              items={items}
              appearancesOk={appearancesOk}
              isToday={todayDay === day}
              onSelect={openDay}
            />
          );
        })}
      </div>

      <dialog
        ref={dialogRef}
        className="day-sheet"
        aria-labelledby={titleId}
        closedby="any"
        onClose={handleClose}
        onClick={(event) => {
          if (event.target === event.currentTarget) {
            event.currentTarget.close();
          }
        }}
      >
        <div className="px-4 pt-3 pb-6">
          <div className="mx-auto mb-3 h-1 w-10 rounded-chip bg-line" />
          <div className="flex items-start justify-between gap-3 mb-4">
            <h3 id={titleId} className="text-base font-bold tabular-nums pt-1">
              {selectedIso === null ? (
                ""
              ) : (
                <time dateTime={selectedIso}>{formatIsoDateWithWeekday(selectedIso)}</time>
              )}
            </h3>
            <button
              type="button"
              className="min-w-11 min-h-11 rounded-card text-sm font-normal text-muted hover:bg-canvas"
              onClick={() => dialogRef.current?.close()}
            >
              閉じる
            </button>
          </div>
          {selectedIso !== null && emptyCopy !== null && (
            <p className="text-sm text-muted py-4">{emptyCopy}</p>
          )}
          {children}
        </div>
      </dialog>
    </SelectedIsoContext.Provider>
  );
}

function TodayControl({
  isCurrentMonth,
  onOpenToday,
}: {
  isCurrentMonth: boolean;
  onOpenToday: () => void;
}) {
  const className =
    "shrink-0 min-h-11 px-3 rounded-card bg-surface shadow-card text-sm font-bold hover:bg-canvas";

  if (isCurrentMonth) {
    return (
      <button type="button" className={className} onClick={onOpenToday} aria-label="今日に戻る">
        今日
      </button>
    );
  }

  return (
    <Link href="/" className={`${className} inline-flex items-center`} aria-label="今日に戻る">
      今日
    </Link>
  );
}

function MonthLink({
  target,
  label,
  glyph,
}: {
  target: { year: number; month: number } | null;
  label: string;
  glyph: string;
}) {
  if (target === null) {
    return (
      <span className="min-w-11 min-h-11 flex items-center justify-center text-muted">
        {glyph}
      </span>
    );
  }
  return (
    <Link
      href={`/${target.year}/${String(target.month).padStart(2, "0")}`}
      aria-label={label}
      className="min-w-11 min-h-11 flex items-center justify-center rounded-card bg-surface shadow-card hover:bg-canvas"
    >
      {glyph}
    </Link>
  );
}

function DayCell({
  day,
  iso,
  items,
  appearancesOk,
  isToday,
  onSelect,
}: {
  day: number;
  iso: string;
  items: ChipItem[];
  appearancesOk: boolean;
  isToday: boolean;
  onSelect: (iso: string) => void;
}) {
  const visible = items.slice(0, MAX_VISIBLE_CHIPS);
  const overflow = items.length - visible.length;
  const countLabel = dayCellCountLabel(appearancesOk, items.length);
  const ariaLabel = `${day}日${isToday ? " 今日" : ""} ${countLabel}`;

  return (
    <button
      type="button"
      onClick={() => onSelect(iso)}
      aria-label={ariaLabel}
      aria-haspopup="dialog"
      className="flex min-h-11 min-w-0 w-full flex-col items-stretch rounded-card px-0.5 py-1 text-left hover:bg-surface focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
    >
      <span className="flex min-w-0 flex-wrap items-center gap-x-0.5">
        <span
          className={
            isToday
              ? "inline-flex size-6 shrink-0 items-center justify-center rounded-full bg-ink text-canvas text-xs font-bold tabular-nums"
              : "inline-flex size-6 shrink-0 items-center justify-center text-xs tabular-nums"
          }
        >
          {day}
        </span>
        {isToday && <span className="text-[10px] font-bold text-accent">今日</span>}
        {items.length > 0 && (
          <span className="text-[10px] font-bold text-muted tabular-nums" aria-hidden="true">
            {items.length}
          </span>
        )}
      </span>
      <span className="mt-0.5 flex min-w-0 flex-col gap-0.5">
        {visible.map((a) => (
          <span
            key={a.id}
            title={a.eventName}
            className={`block max-w-full truncate rounded-chip px-0.5 text-[9px] leading-3 ${chipClass(a.eventName)}`}
          >
            {chipLabel(a.performanceStartTime)}
          </span>
        ))}
        {overflow > 0 && (
          <span className="text-[10px] text-muted tabular-nums">他 {overflow} 件</span>
        )}
      </span>
    </button>
  );
}
