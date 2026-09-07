"use client";

import { createContext, useContext, useEffect, useId, useRef, useState } from "react";
import Link from "next/link";
import type { PointerEvent as ReactPointerEvent, ReactNode } from "react";
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

/** つまんで下げて閉じる距離のしきい値（px）。これ未満なら元に戻す。 */
const DISMISS_DISTANCE_PX = 72;
/** 速く弾いたときは距離が足りなくても閉じる（px/ms）。 */
const DISMISS_VELOCITY = 0.5;
/** この距離を超えたら「つまんだ」とみなし、離したときのクリックを捨てる（px）。 */
const DRAG_SLOP_PX = 8;

/** 指の位置をシートのずらし量に直す。下向きだけ、シートの高さまで。 */
function dragOffset(clientY: number, startY: number, height: number): number {
  return Math.min(Math.max(0, clientY - startY), height);
}

type Today = { year: number; month: number; day: number };

/**
 * 開いている日と、その開いた回数。
 *
 * 回数を持つのは、同じ日を選び直したときに state が同値にならないようにするため。
 * `iso` だけだと閉じるアニメーション中の再選択が no-op になり、開き直せなくなる。
 */
type Sheet = { iso: string; seq: number };

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
  const [sheet, setSheet] = useState<Sheet | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const closeCleanupRef = useRef<(() => void) | null>(null);
  const openSeqRef = useRef(0);
  const dragRef = useRef<{
    pointerId: number;
    startY: number;
    lastY: number;
    lastAt: number;
    velocity: number;
    height: number;
  } | null>(null);
  const draggedRef = useRef(false);
  /** ダイアログが実際に表示している seq。まだ開いていない選択と区別するために持つ。 */
  const shownSeqRef = useRef(0);
  const selectedIso = sheet?.iso ?? null;
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
    openSeqRef.current += 1;
    setSheet({ iso, seq: openSeqRef.current });
  };

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog || sheet === null) return;
    if (!dialog.open) dialog.showModal();
    shownSeqRef.current = sheet.seq;
  }, [sheet]);

  useEffect(() => {
    return () => {
      closeCleanupRef.current?.();
    };
  }, []);

  /**
   * シートの位置をずらす。`null` でインラインの指定を外し、CSS に戻す。
   *
   * state ではなく DOM を直接動かす。1 フレームごとに再描画すると指に追従しない。
   */
  const offsetSheet = (px: number | null) => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    dialog.style.translate = px === null ? "" : `0 ${px}px`;
  };

  const beginDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const dialog = dialogRef.current;
    if (!dialog || event.button !== 0) return;
    dragRef.current = {
      pointerId: event.pointerId,
      startY: event.clientY,
      lastY: event.clientY,
      lastAt: event.timeStamp,
      velocity: 0,
      height: dialog.getBoundingClientRect().height,
    };
    draggedRef.current = false;
    event.currentTarget.setPointerCapture(event.pointerId);
    // 指で動かしている間はアニメーションを外す。付いたままだと遅れて追従する
    dialog.style.transition = "none";
  };

  const moveDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag || event.pointerId !== drag.pointerId) return;
    const elapsed = event.timeStamp - drag.lastAt;
    if (elapsed > 0) drag.velocity = (event.clientY - drag.lastY) / elapsed;
    drag.lastY = event.clientY;
    drag.lastAt = event.timeStamp;
    // 上へは動かさない（持ち上げると背景との隙間が見える）。
    // 高さも超えない。超えたまま離すと、閉じる目標値（高さの 100%）まで
    // 一度上へ戻ってから消える
    const offset = dragOffset(event.clientY, drag.startY, drag.height);
    if (offset > DRAG_SLOP_PX) draggedRef.current = true;
    offsetSheet(offset);
  };

  const endDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    const dialog = dialogRef.current;
    if (!drag || !dialog || event.pointerId !== drag.pointerId) return;
    dragRef.current = null;
    const offset = dragOffset(event.clientY, drag.startY, drag.height);
    dialog.style.transition = "";
    if (offset >= DISMISS_DISTANCE_PX || drag.velocity >= DISMISS_VELOCITY) {
      // 先に閉じる。閉じた状態の目標値（translate 0 100%）が決まってから
      // インラインの指定を外すので、指を離した位置から続けて動く
      dialog.close();
    }
    offsetSheet(null);
  };

  const cancelDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    const dialog = dialogRef.current;
    if (!drag || !dialog || event.pointerId !== drag.pointerId) return;
    dragRef.current = null;
    dialog.style.transition = "";
    offsetSheet(null);
  };

  /**
   * 閉じ終わったので選択を捨てる。**閉じ終わっていないなら何もしない。**
   *
   * この関数は close イベントから遅れて呼ばれるため、その間に別の日が
   * 選ばれていることがある。次の 2 つはどちらも「捨ててはいけない」状態:
   *
   * - 既に開き直されている → `open` が立っている
   * - 選ばれたがまだ開いていない → seq が表示中のものより新しい
   */
  const finishClose = () => {
    cancelPendingClose();
    if (dialogRef.current?.open) return;
    setSheet((current) =>
      current === null || current.seq !== shownSeqRef.current ? current : null,
    );
  };

  const handleClose = () => {
    const dialog = dialogRef.current;
    // close は同期発火しない。別の日をタップして開き直したあとに遅れて届くことがあり、
    // その close は既に終わったシートのもの。開いているなら無視する
    if (dialog?.open) return;
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
          {/* つまんで下へスワイプすると閉じる。touch-none が無いと
              ブラウザがスクロールや引っ張って更新に持っていく */}
          <div
            className="-mx-4 px-4 touch-none cursor-grab"
            onPointerDown={beginDrag}
            onPointerMove={moveDrag}
            onPointerUp={endDrag}
            onPointerCancel={cancelDrag}
            onClickCapture={(event) => {
              // つまんで戻しただけのときに「閉じる」を押したことにしない
              if (!draggedRef.current) return;
              draggedRef.current = false;
              event.stopPropagation();
              event.preventDefault();
            }}
          >
            <div className="mx-auto mb-3 h-1 w-10 rounded-chip bg-line" aria-hidden="true" />
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
                className="min-w-11 min-h-11 rounded-card text-sm font-normal text-muted cursor-pointer hover:bg-canvas"
                onClick={() => dialogRef.current?.close()}
              >
                閉じる
              </button>
            </div>
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
