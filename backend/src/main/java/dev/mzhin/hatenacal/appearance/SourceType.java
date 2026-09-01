package dev.mzhin.hatenacal.appearance;

/** 登録経路。docs/data-model.md 第 4.3 節の source_type に対応する。 */
public enum SourceType {
    /** 自動取り込み。抽出元の投稿が必ず紐づく（appearance_auto_requires_post）。 */
    AUTO,
    /** 管理者が手で登録した。 */
    MANUAL
}
