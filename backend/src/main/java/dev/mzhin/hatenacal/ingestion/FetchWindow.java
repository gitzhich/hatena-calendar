package dev.mzhin.hatenacal.ingestion;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 取得範囲。**範囲を持たない取得は表現できない**。
 *
 * <p>「全件取り直し」は X API の 24 時間の重複排除を外れて再課金を生む
 * （docs/x-integration.md「取得位置を後退させない」 / docs/x-integration.md「コスト増を招く実装上の地雷」）。FR-40 は
 * 「{@code since_id} を渡さずに取得する経路が実装に存在しない」ことを求めており、
 * それを型で保証するために sealed にしている。
 */
public sealed interface FetchWindow {

    /** 差分取得。取得済みの最大 tweet_id より新しい投稿だけを取る（通常運用）。 */
    record Since(long sinceId) implements FetchWindow {
        public Since {
            if (sinceId <= 0) {
                throw new IllegalArgumentException("sinceId は正の値です: " + sinceId);
            }
        }
    }

    /**
     * 初回バックフィル。{@code last_fetched_tweet_id} が未設定のときだけ使う。
     * 無制限に遡ると課金が読めないため、開始時刻で範囲を限定する（docs/x-integration.md「初回バックフィル」）。
     */
    record From(OffsetDateTime startTime) implements FetchWindow {
        public From {
            Objects.requireNonNull(startTime, "startTime は必須です");
        }
    }
}
