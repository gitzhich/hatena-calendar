import type { Appearance } from "@/lib/api";

/**
 * その月の出演一覧（FR-03 / FR-04）。
 *
 * **値がない項目は欄ごと出さない。** 空欄や「未定」と表示すると
 * 確定情報だと誤解される（FR-04）。
 */
export function AppearanceList({ appearances }: { appearances: Appearance[] }) {
  if (appearances.length === 0) {
    return (
      <p className="text-sm text-neutral-600 dark:text-neutral-400 py-6">
        この月の出演予定はまだありません。
      </p>
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
        <section key={date} id={date} className="scroll-mt-4">
          <h3 className="text-sm font-bold border-b border-neutral-300 dark:border-neutral-700 pb-1 mb-2 tabular-nums">
            {date.replaceAll("-", "/")}
          </h3>
          <ul className="space-y-4">
            {items.map((a) => (
              <li key={a.id}>
                <AppearanceItem appearance={a} />
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}

function AppearanceItem({ appearance: a }: { appearance: Appearance }) {
  const range = (start: string | null, end: string | null) =>
    start === null ? null : `${start.slice(0, 5)}${end ? `–${end.slice(0, 5)}` : ""}`;

  const performance = range(a.performanceStartTime, a.performanceEndTime);
  const merch = range(a.merchStartTime, a.merchEndTime);

  return (
    <article className="rounded border border-neutral-300 dark:border-neutral-700 p-3">
      <h4 className="font-bold text-sm">{a.eventName}</h4>
      <dl className="mt-2 text-sm space-y-1">
        {a.venueName && <Row label="会場" value={a.venueName} />}
        {performance && <Row label="出演" value={performance} />}
        {merch && <Row label="物販" value={merch} />}
      </dl>
      <p className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs">
        {a.ticketUrl && (
          <External href={a.ticketUrl}>チケット</External>
        )}
        <External href={a.sourceUrl}>出典の投稿</External>
      </p>
    </article>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-2">
      <dt className="shrink-0 w-10 text-neutral-600 dark:text-neutral-400">{label}</dt>
      <dd className="tabular-nums">{value}</dd>
    </div>
  );
}

function External({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="underline min-h-11 inline-flex items-center"
    >
      {children}
    </a>
  );
}
