import Link from "next/link";
import { listVenues, requireAdmin } from "@/lib/admin-api";
import { Pager } from "@/components/admin/Pager";
import { regionLabel } from "@/lib/region";

/**
 * 会場の一覧（FR-10 / ADR-0022）。
 *
 * **1 会場 1 行なので、1 行直せば過去の全出演に効く。** どれを直す価値が大きいかは
 * 出演件数で分かる。並びは地方順で返るため（docs/api.md「会場の一覧と編集」）、
 * 同じ地域の会場を見比べながら直せる。
 */

/** 既定（全件・1 ページ目）には引数を付けない。同じ画面に 2 通りの URL を作らない。 */
function venuesHref(options: { unresolved?: boolean; page?: number }): string {
  const query = new URLSearchParams();
  if (options.unresolved) query.set("unresolved", "true");
  if (options.page !== undefined && options.page > 0) {
    query.set("page", String(options.page));
  }
  const qs = query.toString();
  return qs.length === 0 ? "/admin/venues" : `/admin/venues?${qs}`;
}

function Badge({ children }: { children: React.ReactNode }) {
  return (
    <span className="text-[10px] rounded border border-neutral-400 px-1">{children}</span>
  );
}

export default async function VenuesPage({
  searchParams,
}: {
  searchParams: Promise<{ unresolved?: string; page?: string }>;
}) {
  await requireAdmin();
  const params = await searchParams;
  const unresolved = params.unresolved === "true";
  const page = Number(params.page ?? "0") || 0;
  const result = await listVenues(unresolved, page);

  return (
    <main>
      <h1 className="text-lg font-bold mb-1">会場</h1>
      <p className="text-xs text-neutral-600 dark:text-neutral-400 mb-4">
        会場ごとに 1 行あります。地域を直すと、その会場の出演情報すべてに効きます。
        並びは北から南の順です。
      </p>

      <nav className="mb-4 flex flex-wrap gap-3 text-sm">
        {[
          { label: "すべて", value: false },
          { label: "場所 ID が未解決", value: true },
        ].map((f) => (
          <Link
            key={f.label}
            href={venuesHref({ unresolved: f.value })}
            className={`min-h-11 inline-flex items-center underline ${
              unresolved === f.value ? "font-bold" : ""
            }`}
          >
            {f.label}
          </Link>
        ))}
      </nav>

      {result.items.length === 0 ? (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          該当する会場はありません。
        </p>
      ) : (
        <ul className="space-y-3">
          {result.items.map((v) => (
            <li
              key={v.id}
              className="rounded border border-neutral-300 dark:border-neutral-700 p-3"
            >
              <div className="flex flex-wrap items-baseline gap-2">
                <Badge>{regionLabel(v.region)}</Badge>
                {/* 会場ではなく地域だけの行。出演件数は「会場が未定のままの公演数」 */}
                {v.areaOnly && <Badge>地域のみ</Badge>}
                {/* 立っている行は自動判定・自動解決が触らない（ADR-0022） */}
                {v.manuallyEdited && <Badge>手動編集</Badge>}
              </div>
              <p className="mt-1 text-sm font-bold">
                <Link
                  href={`/admin/venues/${v.id}`}
                  className="min-h-11 inline-flex items-center underline"
                >
                  {v.displayName}
                </Link>
              </p>
              <p className="mt-1 text-xs text-neutral-600 dark:text-neutral-400">
                出演 <span className="tabular-nums">{v.appearanceCount}</span> 件・
                {v.areaOnly
                  ? "場所 ID は持ちません"
                  : v.placeId
                    ? "場所 ID あり"
                    : "場所 ID なし"}
              </p>
            </li>
          ))}
        </ul>
      )}

      <Pager
        page={result.page}
        size={result.size}
        total={result.totalElements}
        href={(p) => venuesHref({ unresolved, page: p })}
      />
    </main>
  );
}
