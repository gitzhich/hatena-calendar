package dev.mzhin.hatenacal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * ヘルスチェックが DB に触らない（ADR-0023 / docs/architecture.md「運用コストの試算」）。
 *
 * <p><b>Fly が /actuator/health を 30 秒ごとに叩く</b>（backend/fly.toml）。
 * db インジケータは {@code Connection.isValid()} で DB に触るため、含めると
 * Neon の scale-to-zero タイマーが 30 秒ごとにリセットされ、
 * <b>5 分の無活動に一度も到達しない</b>。実際これで 2026-09-03 から
 * 11.78 日間 suspend せず、無料枠 100 CU-hours の 70% を 12 日で消費した。
 *
 * <p><b>設定 1 行なので、消えても誰も気づかない。</b>Spring Boot は
 * DataSource があれば db インジケータを既定で有効にするので、
 * {@code management.health.db.enabled: false} を消すと<b>黙って元に戻る</b>。
 * 戻ったことが分かるのは翌月の請求期間に枠が尽きたときになる。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "INTERNAL_API_KEY=public-key",
    "INTERNAL_ADMIN_API_KEY=admin-key",
    // 本番は show-details: never で components を返さない。
    // ここだけ always にしないと db の有無を見られない
    "management.endpoint.health.show-details=always"
})
class HealthCheckHasNoDbIT {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    private String health() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/actuator/health")).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    @DisplayName("health に db コンポーネントが無い。あると 30 秒ごとに DB を起こす")
    void healthHasNoDbComponent() throws Exception {
        String body = health();

        // components そのものは出ている（show-details=always が効いている）。
        // これを確かめないと、details が空なだけの偽の合格になる
        assertThat(body).contains("\"components\"").contains("\"diskSpace\"");

        assertThat(body).doesNotContain("\"db\"");
        // db インジケータの実体。文字列が変わっても気づけるよう両方見る
        assertThat(body).doesNotContain("isValid()");
    }

    @Test
    @DisplayName("db を外しても health は UP を返す。Fly のチェックは通り続ける")
    void healthIsStillUp() throws Exception {
        assertThat(health()).contains("\"status\":\"UP\"");
    }
}
