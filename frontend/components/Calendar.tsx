import type { Appearance } from "@/lib/api";
import { todayInJst, isWithinRange } from "@/lib/calendar-range";
import { DayGrid, DayPanel } from "@/components/DayGrid";
import { AppearanceCard } from "@/components/AppearanceCard";

/** 月グリッド（FR-01）。7 列で当月のすべての日付を含む。 */
export function Calendar({
  year,
  month,
  appearances,
  appearancesOk,
}: {
  year: number;
  month: number;
  appearances: Appearance[];
  appearancesOk: boolean;
}) {
  const byDate: Record<string, Appearance[]> = {};
  for (const a of appearances) {
    const list = byDate[a.appearanceDate] ?? [];
    list.push(a);
    byDate[a.appearanceDate] = list;
  }

  const chipsByDate: Record<
    string,
    { id: number; eventName: string; performanceStartTime: string | null }[]
  > = {};
  for (const [iso, items] of Object.entries(byDate)) {
    chipsByDate[iso] = items.map((a) => ({
      id: a.id,
      eventName: a.eventName,
      performanceStartTime: a.performanceStartTime,
    }));
  }

  const today = todayInJst();
  const prev = month === 1 ? { year: year - 1, month: 12 } : { year, month: month - 1 };
  const next = month === 12 ? { year: year + 1, month: 1 } : { year, month: month + 1 };

  return (
    <section aria-labelledby="calendar-heading">
      <DayGrid
        year={year}
        month={month}
        chipsByDate={chipsByDate}
        appearancesOk={appearancesOk}
        today={today}
        prev={isWithinRange(prev.year, prev.month) ? prev : null}
        next={isWithinRange(next.year, next.month) ? next : null}
      >
        {Object.entries(byDate).map(([iso, items]) => (
          <DayPanel key={iso} iso={iso}>
            <ul className="space-y-3">
              {items.map((a) => (
                <li key={a.id}>
                  <AppearanceCard appearance={a} />
                </li>
              ))}
            </ul>
          </DayPanel>
        ))}
      </DayGrid>
    </section>
  );
}
