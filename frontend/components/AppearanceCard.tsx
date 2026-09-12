import type { ReactNode } from "react";
import type { Appearance } from "@/lib/api";
import { formatTimeRange, performanceLabel } from "@/lib/appearance-display";
import { mapLink } from "@/lib/map-link";
import { regionLabel } from "@/lib/region";

/**
 * 出演 1 件の詳細（FR-04）。
 *
 * 出演時刻が無いときは「時刻未定」と出す。会場・物販・チケットは
 * 値がない欄を出さない。会場があるときは地図リンクを添える（FR-09）。
 * 地域は会場が未定でも出す（FR-10）。
 */
export function AppearanceCard({ appearance: a }: { appearance: Appearance }) {
  const merch = formatTimeRange(a.merchStartTime, a.merchEndTime);

  return (
    <article className="rounded-card border border-line bg-surface shadow-card p-4">
      <h4 className="font-bold text-sm">{a.eventName}</h4>
      <dl className="mt-2 text-sm space-y-1">
        <Row label="地域" value={<span className="text-xs">{regionLabel(a.venueRegion)}</span>} />
        {a.venueName && (
          <Row
            label="会場"
            value={<VenueValue name={a.venueName} placeId={a.venuePlaceId} />}
          />
        )}
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

function VenueValue({ name, placeId }: { name: string; placeId: string | null }) {
  const link = mapLink(name, placeId);
  return (
    <span className="flex min-w-0 flex-col items-start">
      <span className="break-words">{name}</span>
      {link ? (
        <External href={link.href}>
          {link.confirmed ? "地図を開く" : "地図で検索"}
        </External>
      ) : null}
    </span>
  );
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex gap-2">
      <dt className="shrink-0 w-10 text-muted">{label}</dt>
      <dd className="min-w-0 tabular-nums">{value}</dd>
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
