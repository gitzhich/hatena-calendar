import Link from "next/link";
import type { Appearance } from "@/lib/api";
import { todayInJst, isWithinRange } from "@/lib/calendar-range";

const WEEKDAYS = ["日", "月", "火", "水", "木", "金", "土"];

/** 月グリッド（FR-01）。7 列で当月のすべての日付を含む。 */
export function Calendar({
  year,
  month,
  appearances,
}: {
  year: number;
  month: number;
  appearances: Appearance[];
}) {
  const byDate = new Map<string, Appearance[]>();
  for (const a of appearances) {
    const list = byDate.get(a.appearanceDate) ?? [];
    list.push(a);
    byDate.set(a.appearanceDate, list);
  }

  const firstWeekday = new Date(Date.UTC(year, month - 1, 1)).getUTCDay();
  const daysInMonth = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const today = todayInJst();

  // 前後の空きマスを含めてグリッドを埋める
  const cells: (number | null)[] = [
    ...Array<null>(firstWeekday).fill(null),
    ...Array.from({ length: daysInMonth }, (_, i) => i + 1),
  ];
  while (cells.length % 7 !== 0) cells.push(null);

  const pad = (n: number) => String(n).padStart(2, "0");
  const prev = month === 1 ? { y: year - 1, m: 12 } : { y: year, m: month - 1 };
  const next = month === 12 ? { y: year + 1, m: 1 } : { y: year, m: month + 1 };

  return (
    <section aria-labelledby="calendar-heading">
      <nav className="flex items-center justify-between gap-2 mb-4">
        <MonthLink year={prev.y} month={prev.m} label="前の月" glyph="←" />
        <h2 id="calendar-heading" className="text-lg font-bold tabular-nums">
          {year}年{month}月
        </h2>
        <MonthLink year={next.y} month={next.m} label="次の月" glyph="→" />
      </nav>

      <div className="grid grid-cols-7 gap-px bg-neutral-300 dark:bg-neutral-700 border border-neutral-300 dark:border-neutral-700">
        {WEEKDAYS.map((w) => (
          <div
            key={w}
            className="bg-neutral-100 dark:bg-neutral-800 text-center text-xs py-2 font-medium"
          >
            {w}
          </div>
        ))}

        {cells.map((day, i) => {
          if (day === null) {
            // 当月の日付と視覚的に区別する（FR-01）
            return (
              <div
                key={`empty-${i}`}
                className="bg-neutral-50 dark:bg-neutral-900 min-h-[3.5rem]"
                aria-hidden="true"
              />
            );
          }
          const iso = `${year}-${pad(month)}-${pad(day)}`;
          const items = byDate.get(iso) ?? [];
          const isToday =
            today.year === year && today.month === month && today.day === day;

          return (
            <DayCell key={iso} day={day} iso={iso} items={items} isToday={isToday} />
          );
        })}
      </div>
    </section>
  );
}

function MonthLink({
  year,
  month,
  label,
  glyph,
}: {
  year: number;
  month: number;
  label: string;
  glyph: string;
}) {
  // 表示範囲の外へは進めない（FR-05 / ADR-0014）
  if (!isWithinRange(year, month)) {
    return (
      <span className="min-w-11 min-h-11 flex items-center justify-center text-neutral-400">
        {glyph}
      </span>
    );
  }
  return (
    <Link
      href={`/${year}/${String(month).padStart(2, "0")}`}
      aria-label={label}
      // タップ対象 44x44px 以上（NFR-06）
      className="min-w-11 min-h-11 flex items-center justify-center rounded border border-neutral-300 dark:border-neutral-600 hover:bg-neutral-100 dark:hover:bg-neutral-800"
    >
      {glyph}
    </Link>
  );
}

function DayCell({
  day,
  iso,
  items,
  isToday,
}: {
  day: number;
  iso: string;
  items: Appearance[];
  isToday: boolean;
}) {
  return (
    <div
      className={`bg-white dark:bg-neutral-950 min-h-[3.5rem] p-1 ${
        isToday ? "ring-2 ring-inset ring-sky-600 dark:ring-sky-400" : ""
      }`}
    >
      <div className="flex items-baseline gap-1">
        <span className="text-xs tabular-nums">{day}</span>
        {isToday && <span className="text-[10px] text-sky-700 dark:text-sky-300">今日</span>}
        {/* 件数を数字で出す。色だけに依存しない（FR-02 / NFR-08） */}
        {items.length > 0 && (
          <span className="text-[10px] font-bold" aria-label={`出演 ${items.length} 件`}>
            ●{items.length}
          </span>
        )}
      </div>
      <ul className="mt-0.5 space-y-0.5">
        {items.map((a) => (
          <li key={a.id}>
            <a
              href={`#${iso}`}
              className="block text-[10px] leading-tight truncate underline decoration-dotted"
              title={a.eventName}
            >
              {a.performanceStartTime ? a.performanceStartTime.slice(0, 5) : "時刻未定"}
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
