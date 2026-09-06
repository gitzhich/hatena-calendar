import type { ReactNode } from "react";
import type { Appearance } from "@/lib/api";
import { formatTimeRange, performanceLabel } from "@/lib/appearance-display";

/**
 * 出演 1 件の詳細（FR-04）。
 *
 * 出演時刻が無いときは「時刻未定」と出す。会場・物販・チケットは
 * 値がない欄を出さない。
 */
export function AppearanceCard({ appearance: a }: { appearance: Appearance }) {
  const merch = formatTimeRange(a.merchStartTime, a.merchEndTime);

  return (
    <article className="rounded-card border border-line bg-surface shadow-card p-4">
      <h4 className="font-bold text-sm">{a.eventName}</h4>
      <dl className="mt-2 text-sm space-y-1">
        {a.venueName && <Row label="会場" value={a.venueName} />}
        <Row
          label="出演"
          value={
            a.performanceStartTime === null ? (
              performanceLabel(a.performanceStartTime, a.performanceEndTime)
            ) : (
              <time dateTime={a.performanceStartTime}>
                {performanceLabel(a.performanceStartTime, a.performanceEndTime)}
              </time>
            )
          }
        />
        {merch && a.merchStartTime !== null && (
          <Row
            label="物販"
            value={<time dateTime={a.merchStartTime}>{merch}</time>}
          />
        )}
      </dl>
      <p className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs">
        {a.ticketUrl && <External href={a.ticketUrl}>チケット</External>}
        <External href={a.sourceUrl}>出典の投稿</External>
      </p>
    </article>
  );
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex gap-2">
      <dt className="shrink-0 w-10 text-muted">{label}</dt>
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
      className="underline min-h-11 inline-flex items-center text-accent"
    >
      {children}
    </a>
  );
}
