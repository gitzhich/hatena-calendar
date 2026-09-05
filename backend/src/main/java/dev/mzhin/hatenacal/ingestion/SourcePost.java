package dev.mzhin.hatenacal.ingestion;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 情報源から取得した 1 投稿。取得層と抽出層の境界に置く型で、
 * X API のレスポンス構造をこの先へ持ち込まない。
 */
public record SourcePost(long id, String text, String noteText, OffsetDateTime createdAt) {

    public SourcePost {
        Objects.requireNonNull(text, "text は必須です");
        Objects.requireNonNull(createdAt, "createdAt は必須です");
    }

    /**
     * 抽出に使う本文。
     *
     * <p>{@code text} は途中で切れ、末尾が {@code https://t.co/...} に置き換わる。
     * 実測では長文投稿の {@code text} が 233 字に対し {@code note_tweet} が 492 字で、
     * 物販の行の途中で切れていた（docs/x-integration.md「本文の取り出し」）。
     * {@code note_tweet} があれば必ずそちらを使う。
     */
    public String body() {
        return noteText != null && !noteText.isBlank() ? noteText : text;
    }
}
