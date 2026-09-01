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

/**
 * <b>API キーが未設定のまま起動した場合</b>の挙動。
 *
 * <p>設定漏れは現実に起きる。そのとき「空文字のキーを送れば通る」状態に
 * なっていると、設定ミスがそのまま公開事故になる。
 * 未設定なら誰も通れない、が正しい倒れ方。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MissingApiKeyIT {

    private static final String PUBLIC_PATH =
            "/api/public/appearances?from=2026-09-01&to=2026-09-30";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    private int status(String path, String header, String value)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path)).GET();
        if (header != null) {
            b.header(header, value);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    @DisplayName("キー未設定なら、空文字のヘッダを送っても公開 API は通らない")
    void emptyKeyDoesNotMatchUnsetKey() throws Exception {
        assertThat(status(PUBLIC_PATH, ApiKeyFilter.PUBLIC_HEADER, "")).isEqualTo(403);
    }

    @Test
    @DisplayName("キー未設定なら、空文字のヘッダを送っても管理 API は通らない")
    void emptyKeyDoesNotReachAdminApi() throws Exception {
        assertThat(status("/api/admin/appearances", ApiKeyFilter.ADMIN_HEADER, ""))
                .isEqualTo(403);
    }

    @Test
    @DisplayName("ヘッダなしも当然通らない")
    void noHeaderIsDenied() throws Exception {
        assertThat(status(PUBLIC_PATH, null, null)).isEqualTo(403);
    }
}
