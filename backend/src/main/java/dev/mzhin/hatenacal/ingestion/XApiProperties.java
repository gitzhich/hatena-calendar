package dev.mzhin.hatenacal.ingestion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * X API 連携の設定。既定値は {@code application.yml} 側に置く。
 *
 * <p>{@code bearerToken} は<b>課金に直結するシークレット</b>である。
 * ログ・エラーレスポンス・フロントエンドに出さない（NFR-03 / docs/security.md T-01）。
 * <b>この型に {@code toString()} を生やさない</b>。record の既定 {@code toString()} は
 * 全フィールドを出すため、意図せずログに載る経路を作らないよう明示的に上書きする。
 */
@ConfigurationProperties(prefix = "x")
public record XApiProperties(
        String bearerToken,
        String sourceUsername,
        String baseUrl,
        int maxResults,
        int maxPages,
        int maxRetries,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration connectTimeout,
        Duration readTimeout,
        int backfillMonths,
        int billingCycleStartDay) {

    /**
     * 取り込みを実行できる状態か。
     *
     * <p>トークンが無くても<b>アプリは起動する</b>。公開カレンダーは
     * X API に依存しない（NFR-02）。取り込みだけをスキップする。
     */
    public boolean configured() {
        return bearerToken != null && !bearerToken.isBlank();
    }

    /** シークレットを含む値をログに出さないため、内容を伏せる。 */
    @Override
    public String toString() {
        return "XApiProperties[bearerToken=<redacted>, sourceUsername=" + sourceUsername
                + ", baseUrl=" + baseUrl + ", maxResults=" + maxResults
                + ", maxPages=" + maxPages + ", maxRetries=" + maxRetries + "]";
    }
}
