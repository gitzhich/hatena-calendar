package dev.mzhin.hatenacal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mzhin.hatenacal.config.ApiKeyFilter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理者パスワードの検証（FR-20 / docs/api.md「管理者パスワードの検証」）。
 *
 * <p>ハッシュは実行時に生成する。固定値を埋め込むと、BCrypt の実装や
 * コストが変わったときに何が壊れたのか分からなくなる。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "INTERNAL_ADMIN_API_KEY=admin-key")
class InternalAuthApiIT {

    private static final String ADMIN_KEY = "admin-key";
    private static final String CORRECT_PASSWORD = "correct-horse-battery-staple";

    @DynamicPropertySource
    static void adminPassword(DynamicPropertyRegistry registry) {
        registry.add("ADMIN_PASSWORD_HASH",
                () -> new BCryptPasswordEncoder().encode(CORRECT_PASSWORD));
    }
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    private HttpResponse<String> auth(String password) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/internal/auth"))
                .header(ApiKeyFilter.ADMIN_HEADER, ADMIN_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"password\":\"" + password + "\"}"))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("正しいパスワードなら authenticated: true")
    void correctPassword() throws Exception {
        HttpResponse<String> res = auth(CORRECT_PASSWORD);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(json.readTree(res.body()).get("authenticated").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("誤ったパスワードでも 200 を返す。ステータスで成否を区別しない")
    void wrongPasswordStillReturns200() throws Exception {
        HttpResponse<String> res = auth("wrong-password");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(json.readTree(res.body()).get("authenticated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("応答にパスワードやハッシュを含めない")
    void responseLeaksNothing() throws Exception {
        String body = auth("wrong-password").body();
        assertThat(body).doesNotContain("$2a$", "wrong-password", CORRECT_PASSWORD);
    }
}
