package dev.mzhin.hatenacal.ingestion;

/** 取り込み実行の状態（docs/data-model.md 第 4.3 節）。 */
public enum IngestionRunStatus {
    RUNNING,
    SUCCESS,
    FAILED
}
