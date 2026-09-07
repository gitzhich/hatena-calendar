import Link from "next/link";
import { listAppearances, listIngestionRuns, requireAdmin } from "@/lib/admin-api";
import {
  APPEARANCE_SORT_OPTIONS,
  adminAppearancesHref,
  parseAppearanceSort,
} from "@/lib/admin-appearance-query";
import { IngestionAlert } from "@/components/admin/IngestionAlert";
import { Pager } from "@/components/admin/Pager";

/**
 * 自動登録された出演情報の点検（FR-24）。
 *
 * <b>公開の可否を決める承認手続きではない。</b> 承認フローは持たず、
 * 登録された時点で公開されている（ADR-0003）。ここは公開後の事後点検。
 */
export default async function AdminHome({
  searchParams,
}: {
  searchParams: Promise<{ sourceType?: string; page?: string; sort?: string }>;
}) {
  await requireAdmin();
  const params = await searchParams;
  const sourceType = params.sourceType === "AUTO" || params.sourceType === "MANUAL"
    ? params.sourceType
    : undefined;
  const sort = parseAppearanceSort(params.sort);
  const page = Number(params.page ?? "0") || 0;
  const [result, ingestion] = await Promise.all([
    listAppearances(sourceType, page, sort),
    // 警告に必要なのは判定だけ。履歴そのものは専用ページで見るので 1 件で足りる
    listIngestionRuns(0, 1),
  ]);

  return (
    <main>
      {/* 取り込みが止まっていることに、ここへ来た時点で気づけるようにする（NFR-09） */}
      <IngestionAlert
        consecutiveFailureCount={ingestion.consecutiveFailureCount}
        halted={ingestion.halted}
        withLink
      />

      <h1 className="text-lg font-bold mb-1">出演情報の点検</h1>
      <p className="text-xs text-neutral-600 dark:text-neutral-400 mb-4">
        登録された時点で公開されています。誤りがあればその場で修正してください。
      </p>

      <nav className="mb-2 flex flex-wrap gap-3 text-sm">
        {[
          { label: "すべて", value: undefined },
          { label: "自動登録", value: "AUTO" as const },
          { label: "手動登録", value: "MANUAL" as const },
        ].map((f) => (
          <Link
            key={f.label}
            href={adminAppearancesHref({ sourceType: f.value, sort })}
            className={`min-h-11 inline-flex items-center underline ${
              sourceType === f.value ? "font-bold" : ""
            }`}
          >
            {f.label}
          </Link>
        ))}
      </nav>

      <nav aria-label="並び順" className="mb-4 flex flex-wrap gap-3 text-sm">
        {APPEARANCE_SORT_OPTIONS.map((option) => (
          <Link
            key={option.value}
            href={adminAppearancesHref({ sourceType, sort: option.value })}
            className={`min-h-11 inline-flex items-center underline ${
              sort === option.value ? "font-bold" : ""
            }`}
          >
            {option.label}
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

      <Pager
        page={result.page}
        size={result.size}
        total={result.totalElements}
        href={(p) => adminAppearancesHref({ sourceType, sort, page: p })}
      />
    </main>
  );
}
