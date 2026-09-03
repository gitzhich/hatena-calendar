import Link from "next/link";

/**
 * 取り込みの連続失敗の警告（NFR-09）。
 *
 * **1 回の失敗から出す。** 429 と 5xx は `XApiHttpClient` が指数バックオフで
 * リトライしており、`FAILED` として記録されるのはリトライを使い切った後
 * （FR-43）。一時的な失敗はここに到達しない。
 *
 * **判定はバックエンドの値をそのまま使う。** 打ち切りの条件を画面側で
 * 書き直すと、実際に止まっている条件とずれても誰も気づけない
 * （`IngestionHaltRule`）。
 *
 * 色ではなく文言で伝える（NFR-08）。
 */
export function IngestionAlert({
  consecutiveFailureCount,
  halted,
  withLink = false,
}: {
  consecutiveFailureCount: number;
  halted: boolean;
  withLink?: boolean;
}) {
  if (consecutiveFailureCount === 0) return null;

  return (
    <div
      role="alert"
      className="mb-4 rounded border border-amber-500 bg-amber-50 dark:bg-amber-950 p-3 text-sm text-neutral-800 dark:text-neutral-200"
    >
      {halted ? (
        <>
          <p>
            <strong>
              取り込みが {consecutiveFailureCount} 回連続で失敗し、停止しています。
            </strong>
          </p>
          <p className="mt-1">
            自動では再開しません。原因を確認してから手で戻してください。
            失敗したまま放置すると、同じ範囲を取り直すことで X API の課金が積み上がります。
          </p>
          <p className="mt-1">
            戻し方は <code>docs/runbook-x-api-setup.md</code> 第 9 章にあります。
          </p>
        </>
      ) : (
        <p>
          取り込みが <strong>{consecutiveFailureCount} 回連続で失敗</strong>しています。
          このまま続くと取り込みが自動的に停止します。
        </p>
      )}
      {withLink && (
        <p className="mt-2">
          <Link href="/admin/ingestion" className="underline">
            取り込み状況を見る
          </Link>
        </p>
      )}
    </div>
  );
}
