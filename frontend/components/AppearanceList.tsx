import type { Appearance } from "@/lib/api";
import { emptyAppearanceListCopy, formatIsoDate } from "@/lib/appearance-display";
import { AppearanceCard } from "@/components/AppearanceCard";
import { RegionFiltered } from "@/components/RegionScope";

/**
 * その月の出演一覧（FR-03 / FR-04）。
 *
 * 出演時刻が無いカードは「時刻未定」と出す。会場・物販・チケットは
 * 値がない欄を出さない（FR-04）。
 */
export function AppearanceList({ appearances }: { appearances: Appearance[] }) {
  if (appearances.length === 0) {
    return (
      <p className="text-sm text-muted py-6">{emptyAppearanceListCopy(false)}</p>
    );
  }

  const byDate = new Map<string, Appearance[]>();
  for (const a of appearances) {
    const list = byDate.get(a.appearanceDate) ?? [];
    list.push(a);
    byDate.set(a.appearanceDate, list);
  }

  return (
    <RegionFiltered
      regions={appearances.map((a) => a.venueRegion)}
      fallback={
        <p className="text-sm text-muted py-6">{emptyAppearanceListCopy(true, true)}</p>
      }
    >
      <div className="space-y-6">
        {[...byDate.entries()].map(([date, items]) => (
          <RegionFiltered key={date} regions={items.map((a) => a.venueRegion)}>
            <section>
              <h3 className="text-sm font-bold mb-2 tabular-nums text-ink">
                <time dateTime={date}>{formatIsoDate(date)}</time>
              </h3>
              <ul className="space-y-3">
                {items.map((a) => (
                  <RegionFiltered key={a.id} regions={[a.venueRegion]}>
                    <li>
                      <AppearanceCard appearance={a} />
                    </li>
                  </RegionFiltered>
                ))}
              </ul>
            </section>
          </RegionFiltered>
        ))}
      </div>
    </RegionFiltered>
  );
}
