package dev.mzhin.hatenacal.ingestion;

/** 取り込み実行の状態（docs/data-model.md「ingestion_run — 取り込み実行ログ」）。 */
public enum IngestionRunStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    /**
     * 管理者が原因を確認し、打ち切りカウントから外した失敗
     * （docs/runbook-x-api-setup.md「打ち切りから戻す」）。
     *
     * <p><b>アプリはこの状態へ遷移させない。</b> 打ち切りからの復帰は原因を
     * 確認してから手で行う運用であり（FR-43 / docs/api.md「取り込み履歴」）、
     * 遷移は SQL で行う。ここで読めるようにしているだけ。
     */
    CANCELLED
}
