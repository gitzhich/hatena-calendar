import { listIngestionRuns, requireAdmin, type IngestionRun } from "@/lib/admin-api";
import { IngestionAlert } from "@/components/admin/IngestionAlert";
import { Pager } from "@/components/admin/Pager";
import { estimateUsd, MONTHLY_BUDGET_USD, overBudget } from "@/lib/ingestion-cost";
import { unparsedCountLabel } from "@/lib/ingestion-run-display";
import { formatJst } from "@/lib/last-updated";

/**
 * 取り込みの状況（NFR-04 / NFR-09 / FR-42）。
 *
 * <b>消費リソース数は X API の課金単位そのもの</b>で、想定を超えていないかを
 * ここで確認する。実行ごとの成否と失敗理由も併せて出し、
 * 「止まっていることに気づく」から「なぜ止まったかを見る」までを 1 画面で終える。
 *
 * <b>再開のボタンは置かない。</b> 打ち切りからの復帰は原因を確認してから
 * 手で戻す運用（FR-43）。押すだけで再開できると、原因が残ったまま
 * 同じ範囲を取り直して課金が積み上がる。
 */
export default async function IngestionPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string }>;
}) {
  await requireAdmin();
  const page = Number((await searchParams).page ?? "0") || 0;
  const result = await listIngestionRuns(page);

  return (
    <main>
      <h1 className="text-lg font-bold mb-1">取り込み状況</h1>
      <p className="text-xs text-neutral-600 dark:text-neutral-400 mb-4">
        X API からの自動取り込みの実行記録です。
      </p>

      <IngestionAlert
        consecutiveFailureCount={result.consecutiveFailureCount}
        halted={result.halted}
      />

      <CostSummary
        resources={result.currentCycleResourceCount}
        cycleStartAt={result.cycleStartAt}
      />

      <h2 className="mt-6 mb-2 text-sm font-bold">実行履歴</h2>
      {result.items.length === 0 ? (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          実行記録はまだありません。
        </p>
      ) : (
        <ul className="space-y-3">
          {result.items.map((run) => (
            <RunItem key={run.id} run={run} />
          ))}
        </ul>
      )}

      <Pager
        page={result.page}
        size={result.size}
        total={result.totalElements}
        href={(p) => `/admin/ingestion?page=${p}`}
      />
    </main>
  );
}

/**
 * 現在の請求サイクルの消費と概算コスト（NFR-04）。
 *
 * <b>期間を明示する。</b> X の請求サイクルはクレジットの購入日を起点に切られ、
 * 暦月と一致しない（docs/runbook-x-api-setup.md「コンソールで紛らわしい点」）。
 * 「当月」とだけ書くと、支出上限のリセット日とずれた値を月の合計だと読まれる。
 */
function CostSummary({
  resources,
  cycleStartAt,
}: {
  resources: number;
  cycleStartAt: string;
}) {
  return (
    <section className="rounded border border-neutral-300 dark:border-neutral-700 p-3">
      <h2 className="text-sm font-bold">今の請求サイクルの消費</h2>
      <p className="text-xs text-neutral-600 dark:text-neutral-400">
        {formatJst(cycleStartAt) ?? "起点不明"} 以降の合計
      </p>
      <p className="mt-1 text-sm tabular-nums">
        {resources.toLocaleString("ja-JP")} リソース
        <span className="text-neutral-600 dark:text-neutral-400">
          {" "}
          ≒ {estimateUsd(resources)}
        </span>
      </p>
      {overBudget(resources) && (
        // 色ではなく文言で伝える（NFR-08）
        <p
          role="alert"
          className="mt-2 rounded border border-amber-500 bg-amber-50 dark:bg-amber-950 p-2 text-sm"
        >
          <strong>
            想定していた 1 サイクル ${MONTHLY_BUDGET_USD} を超えています。
          </strong>
          取得範囲が広がっていないか確認してください。
        </p>
      )}
      <p className="mt-2 text-xs text-neutral-600 dark:text-neutral-400">
        請求サイクルの起点は設定値（<code>x.billing-cycle-start-day</code>）です。
        X 側でサイクルが変わったら合わせてください。正確な金額は X の管理画面で確認できます。
      </p>
    </section>
  );
}

const STATUS_LABEL: Record<IngestionRun["status"], string> = {
  RUNNING: "実行中",
  SUCCESS: "成功",
  FAILED: "失敗",
  // 失敗した事実は消さない。打ち切りカウントから外しただけであることを見せる
  CANCELLED: "失敗（確認済み）",
};

function RunItem({ run }: { run: IngestionRun }) {
  // バックエンドは UTC で返す。JST への変換は表示側の責務（docs/data-model.md「タイムゾーンの扱い」）
  const startedAt = formatJst(run.startedAt);

  return (
    <li className="rounded border border-neutral-300 dark:border-neutral-700 p-3">
      <div className="flex flex-wrap items-baseline gap-2">
        {startedAt && (
          <time dateTime={run.startedAt} className="text-sm tabular-nums font-medium">
            {startedAt}
          </time>
        )}
        <span className="text-[10px] rounded border border-neutral-400 px-1">
          {STATUS_LABEL[run.status]}
        </span>
      </div>
      <p className="mt-1 text-xs tabular-nums text-neutral-600 dark:text-neutral-400">
        取得 {run.fetchedResourceCount} リソース・新規 {run.newAppearanceCount} 件・未処理 {unparsedCountLabel(run.unparsedCount)}
      </p>
      {run.truncated && (
        /*
          打ち切りは status が SUCCESS のままなので、状態バッジには出ない。
          ここで出さないと取りこぼしに気づけない（NFR-09 / ADR-0020）
        */
        <p
          role="alert"
          className="mt-2 rounded border border-amber-500 bg-amber-50 dark:bg-amber-950 p-2 text-xs"
        >
          <strong>1 回の上限に達したため打ち切りました。</strong>
          この実行より古い投稿は取り込まれず、取り直しもされません。
          抜けている出演情報は手動で登録してください。
        </p>
      )}
      {run.errorSummary && (
        // 要約のみで、スタックトレースとトークンは含まれない（NFR-03）
        <p className="mt-2 text-xs break-words">{run.errorSummary}</p>
      )}
    </li>
  );
}
