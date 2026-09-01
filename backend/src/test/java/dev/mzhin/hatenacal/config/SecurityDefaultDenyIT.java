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
 * デフォルト拒否（NFR-03）。
 *
 * <p>明示的に許可していないパスが拒否されることを確かめる。
 * これが効いていないと、新しいコントローラを足して認可規則を書き忘れたとき、
 * <b>気づかないまま公開されてしまう</b>。許可漏れではなく拒否漏れを防ぐ設計。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "INTERNAL_API_KEY=public-key",
    "INTERNAL_ADMIN_API_KEY=admin-key"
})
class SecurityDefaultDenyIT {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    private int status(String path, String header, String key)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path)).GET();
        if (header != null) {
            b.header(header, key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    @DisplayName("認可規則のないパスは、管理キーを持っていても 403")
    void unmappedPathIsDeniedEvenWithAdminKey() throws Exception {
        // 403 であって 404 ではないこと。permitAll に変えると 404 になり、
        // 「規則を書き忘れたエンドポイントが素通りする」状態を意味する
        assertThat(status("/api/not-declared/anything", ApiKeyFilter.ADMIN_HEADER, "admin-key"))
                .isEqualTo(403);
        assertThat(status("/some/random/path", ApiKeyFilter.ADMIN_HEADER, "admin-key"))
                .isEqualTo(403);
    }

    @Test
    @DisplayName("公開していない actuator も 403")
    void undisclosedActuatorIsDenied() throws Exception {
        assertThat(status("/actuator/env", ApiKeyFilter.ADMIN_HEADER, "admin-key"))
                .isEqualTo(403);
        assertThat(status("/actuator/beans", ApiKeyFilter.ADMIN_HEADER, "admin-key"))
                .isEqualTo(403);
    }

    @Test
    @DisplayName("health だけは認証なしで通る。監視のため明示的に許可している")
    void healthIsPubliclyAvailable() throws Exception {
        assertThat(status("/actuator/health", null, null)).isEqualTo(200);
    }
}
