package dev.mzhin.hatenacal.appearance;

/** 取り込みが既存データに対して何をしたか（docs/data-model.md「追加告知による空欄補完」）。 */
public enum IngestionOutcome {
    /** 新しい出演情報を登録した。 */
    CREATED,
    /** 既存の行の空欄を埋めた。 */
    COMPLETED,
    /** 既存の行があり、埋める空欄も無かった。 */
    UNCHANGED
}
