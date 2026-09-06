import type { Appearance } from "@/lib/api";
import { formatIsoDate } from "@/lib/appearance-display";
import { AppearanceCard } from "@/components/AppearanceCard";

/**
 * その月の出演一覧（FR-03 / FR-04）。
 *
 * 出演時刻が無いカードは「時刻未定」と出す。会場・物販・チケットは
 * 値がない欄を出さない（FR-04）。
 */
export function AppearanceList({ appearances }: { appearances: Appearance[] }) {
  if (appearances.length === 0) {
    return (
      <p className="text-sm text-muted py-6">この月の出演予定はまだありません。</p>
    );
  }

  const byDate = new Map<string, Appearance[]>();
  for (const a of appearances) {
    const list = byDate.get(a.appearanceDate) ?? [];
    list.push(a);
    byDate.set(a.appearanceDate, list);
  }

  return (
    <div className="space-y-6">
      {[...byDate.entries()].map(([date, items]) => (
        <section key={date}>
          <h3 className="text-sm font-bold mb-2 tabular-nums text-ink">
            <time dateTime={date}>{formatIsoDate(date)}</time>
          </h3>
          <ul className="space-y-3">
            {items.map((a) => (
              <li key={a.id}>
                <AppearanceCard appearance={a} />
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
