import Link from "next/link";
import { listAppearances, requireAdmin } from "@/lib/admin-api";

/**
 * 自動登録された出演情報の点検（FR-24）。
 *
 * <b>公開の可否を決める承認手続きではない。</b> 承認フローは持たず、
 * 登録された時点で公開されている（ADR-0003）。ここは公開後の事後点検。
 */
export default async function AdminHome({
  searchParams,
}: {
  searchParams: Promise<{ sourceType?: string; page?: string }>;
}) {
  await requireAdmin();
  const params = await searchParams;
  const sourceType = params.sourceType === "AUTO" || params.sourceType === "MANUAL"
    ? params.sourceType
    : undefined;
  const page = Number(params.page ?? "0") || 0;
  const result = await listAppearances(sourceType, page);

  return (
    <main>
      <h1 className="text-lg font-bold mb-1">出演情報の点検</h1>
      <p className="text-xs text-neutral-600 dark:text-neutral-400 mb-4">
        登録された時点で公開されています。誤りがあればその場で修正してください。
      </p>

      <nav className="mb-4 flex gap-3 text-sm">
        {[
          { label: "すべて", value: undefined },
          { label: "自動登録", value: "AUTO" },
          { label: "手動登録", value: "MANUAL" },
        ].map((f) => (
          <Link
            key={f.label}
            href={f.value ? `/admin?sourceType=${f.value}` : "/admin"}
            className={`min-h-11 inline-flex items-center underline ${
              sourceType === f.value ? "font-bold" : ""
            }`}
          >
            {f.label}
          </Link>
        ))}
      </nav>

      {result.items.length === 0 ? (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          該当する出演情報はありません。
        </p>
      ) : (
        <ul className="space-y-3">
          {result.items.map((a) => (
            <li
              key={a.id}
              className="rounded border border-neutral-300 dark:border-neutral-700 p-3"
            >
              <div className="flex flex-wrap items-baseline gap-2">
                <span className="text-sm tabular-nums font-medium">
                  {a.appearanceDate}
                </span>
                <span className="text-[10px] rounded border border-neutral-400 px-1">
                  {a.sourceType === "AUTO" ? "自動" : "手動"}
                </span>
                {a.performanceStartTime && (
                  <span className="text-xs tabular-nums">
                    {a.performanceStartTime.slice(0, 5)}
                  </span>
                )}
              </div>
              <p className="mt-1 text-sm font-bold">{a.eventName}</p>
              {a.venueName && <p className="text-xs">{a.venueName}</p>}
              <p className="mt-2 flex flex-wrap gap-4 text-xs">
                <Link href={`/admin/appearances/${a.id}`} className="underline">
                  編集・削除
                </Link>
                {/* 抽出元と突き合わせて確認できるようにする（FR-24） */}
                <a
                  href={a.sourceUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="underline"
                >
                  出典の投稿
                </a>
              </p>
            </li>
          ))}
        </ul>
      )}

      <Pager page={result.page} size={result.size} total={result.totalElements}
             sourceType={sourceType} />
    </main>
  );
}

function Pager({
  page,
  size,
  total,
  sourceType,
}: {
  page: number;
  size: number;
  total: number;
  sourceType?: string;
}) {
  const last = Math.max(0, Math.ceil(total / size) - 1);
  const href = (p: number) =>
    sourceType ? `/admin?sourceType=${sourceType}&page=${p}` : `/admin?page=${p}`;
  return (
    <nav className="mt-6 flex items-center justify-between text-sm">
      {page > 0 ? (
        <Link href={href(page - 1)} className="min-h-11 inline-flex items-center underline">
          ← 前
        </Link>
      ) : (
        <span />
      )}
      <span className="text-xs tabular-nums">
        {total === 0 ? "0 件" : `${page + 1} / ${last + 1} ページ・全 ${total} 件`}
      </span>
      {page < last ? (
        <Link href={href(page + 1)} className="min-h-11 inline-flex items-center underline">
          次 →
        </Link>
      ) : (
        <span />
      )}
    </nav>
  );
}
