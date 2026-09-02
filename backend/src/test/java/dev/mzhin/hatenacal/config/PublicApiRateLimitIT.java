package dev.mzhin.hatenacal.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mzhin.hatenacal.support.MovableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * 公開 API の総量制限がワイヤ上でどう見えるか（NFR-03 / docs/security.md T-04）。
 *
 * <p>上限に達するまで叩くのは重いため、カウンタを直接消費してから
 * HTTP で 1 回だけ確かめる。<b>フィルタが繋がっていること</b>と
 * <b>応答の形</b>がここでの関心事で、境界そのものは
 * {@link PublicApiRateLimiterTest} が持つ。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "INTERNAL_API_KEY=test-public-key",
        "INTERNAL_ADMIN_API_KEY=test-admin-key"
})
@Import(PublicApiRateLimitIT.MovableClockConfig.class)
class PublicApiRateLimitIT {

    /**
     * カウンタはアプリ全体で 1 つのため、テスト間で状態が残る。
     * 時計を進めてウィンドウを明けることで、各テストを独立させる。
     */
    @TestConfiguration
    static class MovableClockConfig {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired
    private Clock clock;

    @BeforeEach
    void freshWindow() {
        ((MovableClock) clock).advance(PublicApiRateLimiter.WINDOW.plus(Duration.ofSeconds(1)));
    }

    private static final String PUBLIC =
            "/api/public/appearances?from=2026-09-01&to=2026-09-30";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private PublicApiRateLimiter limiter;

    private HttpResponse<String> get(String path, String header, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path)).GET();
        if (key != null) {
            b.header(header, key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 上限ぶん消費して、次の 1 回が超過になる状態にする。 */
    private void exhaust() {
        for (int i = 0; i < PublicApiRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            limiter.allow();
        }
    }

    @Test
    @DisplayName("上限を超えた公開 API は 429 と Retry-After を返す")
    void tooManyRequests() throws Exception {
        exhaust();
        HttpResponse<String> res = get(PUBLIC, ApiKeyFilter.PUBLIC_HEADER, "test-public-key");

        assertThat(res.statusCode()).isEqualTo(429);
        assertThat(res.headers().firstValue("Retry-After"))
                .as("待つべき時間を伝える")
                .contains(String.valueOf(PublicApiRateLimiter.WINDOW.toSeconds()));
    }

    @Test
    @DisplayName("超過時に内部構造を漏らさない（NFR-03）")
    void tooManyRequestsHidesInternals() throws Exception {
        exhaust();
        String body = get(PUBLIC, ApiKeyFilter.PUBLIC_HEADER, "test-public-key").body();
        assertThat(body).doesNotContain("dev.mzhin", "SELECT", "Exception", "RateLimit");
    }

    @Test
    @DisplayName("管理 API は総量制限に巻き込まれない。止まると訂正できなくなる")
    void adminApiIsNotLimited() throws Exception {
        exhaust();
        assertThat(get("/api/admin/appearances", ApiKeyFilter.ADMIN_HEADER, "test-admin-key")
                .statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("キーの無いリクエストは枠を消費しない。正規の通信を締め出させない")
    void unauthenticatedRequestsDoNotConsumeBudget() throws Exception {
        // 認証を通らないリクエストは DB に到達しないため数える意味がなく、
        // 数えると攻撃者が正規の枠を食い潰せてしまう。
        //
        // **残り 1 枠にしてから試す。** 余裕がある状態で数回叩いても、
        // 消費していてもいなくても結果が変わらず、退行を捕まえられない
        for (int i = 0; i < PublicApiRateLimiter.MAX_REQUESTS_PER_WINDOW - 1; i++) {
            limiter.allow();
        }

        assertThat(get(PUBLIC, ApiKeyFilter.PUBLIC_HEADER, "wrong-key").statusCode())
                .isEqualTo(403);

        assertThat(get(PUBLIC, ApiKeyFilter.PUBLIC_HEADER, "test-public-key").statusCode())
                .as("最後の 1 枠が残っているはず。403 に食われていれば 429 になる")
                .isEqualTo(200);
    }
}
