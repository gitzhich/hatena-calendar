package dev.mzhin.hatenacal.venue;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Places API の設定（ADR-0022 / docs/security.md T-08）。既定値は {@code application.yml} 側に置く。
 *
 * <p><b>{@code apiKey} はシークレットである。</b> 使う SKU（Text Search の IDs Only）は
 * 無料だが、<b>キーは SKU を選ばない</b>。漏れたキーで有料の SKU（Place Details や
 * Geocoding）を叩かれれば課金される。ログ・エラーレスポンス・フロントエンドに出さない。
 *
 * <p>{@code XApiProperties} と同じ理由で {@code toString()} を明示的に伏せる。
 * record の既定の {@code toString()} は全フィールドを出すため、意図せずログに載る経路を作らない。
 */
@ConfigurationProperties(prefix = "places")
public record PlacesProperties(
        String apiKey,
        String baseUrl,
        String languageCode,
        Duration connectTimeout,
        Duration readTimeout) {

    /**
     * place_id を解決できる状態か。
     *
     * <p><b>キーが無くてもアプリは起動する。</b> 未解決の会場は名前検索の地図リンクに
     * 落ちるだけで画面は壊れない（ADR-0022）。解決だけをスキップする。
     */
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** シークレットを含む値をログに出さないため、内容を伏せる。 */
    @Override
    public String toString() {
        return "PlacesProperties[apiKey=<redacted>, baseUrl=" + baseUrl
                + ", languageCode=" + languageCode + "]";
    }
}
