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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * <b>外部ツールが作った BCrypt ハッシュを受け付けるか。</b>
 *
 * <p>運用者は {@code ADMIN_PASSWORD_HASH} を手元で作って環境変数に置く
 * （docs/runbook-x-api-setup.md）。手順書は Python の {@code bcrypt} を
 * 使う形で書いており、これは <b>{@code $2b$}</b> 形式を出す。
 *
 * <p>{@link InternalAuthApiIT} は Spring 自身の {@code BCryptPasswordEncoder}
 * が作ったハッシュしか検証していない。同じ実装で作って同じ実装で照合しても、
 * <b>手順書どおりに作ったハッシュが通るかは分からない</b>。
 * 形式が合わなければ、設定はできているのにログインだけができなくなる。
 *
 * <p>ここで使うハッシュは固定値で、パスワードも公開のテスト用。秘密ではない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "INTERNAL_ADMIN_API_KEY=admin-key")
class ExternalBcryptHashIT {

    private static final String PASSWORD = "correct-horse-battery-staple";

    /**
     * Python の bcrypt が作ったもの。
     *
     * <pre>
     * python3 -c "import bcrypt; print(bcrypt.hashpw(b'...', bcrypt.gensalt(rounds=10)).decode())"
     * </pre>
     */
    private static final String PYTHON_BCRYPT_HASH =
            "$2b$10$kPQkYmFeX60F9VOzkADX/OnwyDYUJqM5UKZ/U.g6E1e68eh11AzVS";

    @DynamicPropertySource
    static void adminPassword(DynamicPropertyRegistry registry) {
        // プロパティ文字列に直接書くと $ の解釈が絡む。定数で渡す
        registry.add("ADMIN_PASSWORD_HASH", () -> PYTHON_BCRYPT_HASH);
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    /**
     * 認証できたか。
     *
     * <p><b>ステータスコードで判定しない。</b>この API は総当たりの判定材料に
     * ならないよう、失敗時も 200 を返す（docs/api.md 第 6.1 節 / T-02）。
     * 200 を見るだけのテストは、認証が失敗していても通ってしまう。
     */
    private boolean authenticated(String password) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/internal/auth"))
                .header(ApiKeyFilter.ADMIN_HEADER, "admin-key")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"password\":\"" + password + "\"}"))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        return json.readTree(res.body()).path("authenticated").asBoolean();
    }

    @Test
    @DisplayName("$2b$ 形式のハッシュで認証が通る")
    void acceptsDollarTwoBHash() throws Exception {
        assertThat(PYTHON_BCRYPT_HASH).startsWith("$2b$");
        assertThat(authenticated(PASSWORD))
                .as("手順書は Python の bcrypt でハッシュを作らせる。"
                        + "$2b$ を受けなければ、設定はできているのにログインだけができない")
                .isTrue();
    }

    @Test
    @DisplayName("$2b$ 形式でも、誤ったパスワードは通らない")
    void stillRejectsWrongPassword() throws Exception {
        assertThat(authenticated("wrong-password"))
                .as("形式の互換性が、照合の甘さになっていないこと")
                .isFalse();
    }
}
