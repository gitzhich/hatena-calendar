package dev.mzhin.hatenacal.ingestion;

/** 取り込み済み投稿の処理状態（docs/data-model.md「ingested_post — 取り込み済み投稿の記録」）。 */
public enum IngestedPostStatus {
    /** 出演情報が登録済み。自動抽出と、管理者が未処理から手で登録した場合の両方を含む。 */
    REGISTERED,
    /** 抽出できず未処理。管理者の手動処理を待つ（FR-25）。 */
    UNPARSED,
    /** 出演告知ではないと管理者が判断した（FR-25）。 */
    EXCLUDED
}
