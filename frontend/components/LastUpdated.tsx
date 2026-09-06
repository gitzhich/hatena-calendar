import type { SiteStatus } from "@/lib/api";
import { formatJst } from "@/lib/last-updated";

/**
 * データの鮮度（FR-08）。
 *
 * **一度も取り込みが成功していなければ何も出さない。** 警告は日時に併記するもので、
 * 併記する日時が無い場面で警告だけを出しても閲覧者は行動を決められない。
 * 手動登録だけで運用している間ずっと警告が出続けるのも避けたい
 * （常時出ている警告は読み飛ばされる）。
 *
 * ここでは 24 時間の判定をしない。基準はバックエンドが持つ（docs/api.md「データの状態」）。
 */
export function LastUpdated({ status }: { status: SiteStatus }) {
  const iso = status.lastSuccessfulIngestionAt;
  const formatted = iso === null ? null : formatJst(iso);
  if (iso === null || formatted === null) return null;

  return (
    <section className="mt-8 text-xs text-muted">
      <p>
        自動取り込みの最終更新{" "}
        <time dateTime={iso} className="tabular-nums">
          {formatted}
        </time>
      </p>
      {/* 色ではなく文言で伝える（NFR-08） */}
      {status.stale && (
        <p className="mt-2 rounded-card border border-warn-line bg-warn-bg p-3 text-warn-ink">
          24 時間以上、自動取り込みが成功していません。
          <strong>この後に出た告知が反映されていない可能性があります。</strong>
        </p>
      )}
    </section>
  );
}
