package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mzhin.hatenacal.config.ApiKeyFilter;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 未処理投稿（FR-25 / docs/api.md 第 5.5・5.6 節）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "INTERNAL_ADMIN_API_KEY=admin-key",
    "X_SOURCE_USERNAME=xinxin_official"
})
class UnparsedPostApiIT {

    private static final String ADMIN_KEY = "admin-key";
    private static final String PATH = "/api/admin/unparsed-posts";

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    private long postId;

    @BeforeEach
    void seed() {
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM appearance").executeUpdate();
            em.createNativeQuery("DELETE FROM ingested_post").executeUpdate();
            em.createNativeQuery("DELETE FROM source_account").executeUpdate();
            em.createNativeQuery(
                    "INSERT INTO source_account (username, x_user_id) VALUES ('xinxin', 1)")
                    .executeUpdate();
        });
        tx.executeWithoutResult(s -> {
            Long accountId = (Long) em.createNativeQuery(
                    "SELECT id FROM source_account LIMIT 1").getSingleResult();
            em.createNativeQuery("""
                    INSERT INTO ingested_post (source_account_id, tweet_id, posted_at, status)
                    VALUES (?, 1962000000000000001, ?, 'UNPARSED')
                    """)
                    .setParameter(1, accountId)
                    .setParameter(2, OffsetDateTime.of(2026, 8, 30, 12, 0, 0, 0,
                            ZoneOffset.ofHours(9)))
                    .executeUpdate();
        });
        postId = tx.execute(s -> ((Number) em.createNativeQuery(
                "SELECT id FROM ingested_post LIMIT 1").getSingleResult()).longValue());
    }

    @AfterEach
    void clean() {
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM appearance").executeUpdate();
            em.createNativeQuery("DELETE FROM ingested_post").executeUpdate();
            em.createNativeQuery("DELETE FROM source_account").executeUpdate();
        });
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path))
                .header(ApiKeyFilter.ADMIN_HEADER, ADMIN_KEY)
                .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("本文を返さない。tweetId は文字列で返す（精度落ち防止）")
    void listShapeIsSafe() throws Exception {
        JsonNode item = json.readTree(send("GET", PATH, null).body()).get("items").get(0);
        assertThat(item.has("body")).isFalse();
        assertThat(item.has("text")).isFalse();
        assertThat(item.get("tweetId").isString()).isTrue();
        assertThat(item.get("tweetId").asString()).isEqualTo("1962000000000000001");
        assertThat(item.get("postUrl").asString())
                .isEqualTo("https://x.com/xinxin_official/status/1962000000000000001");
    }

    @Test
    @DisplayName("対象外にすると一覧から消える（FR-25）")
    void excludeRemovesFromList() throws Exception {
        assertThat(send("POST", PATH + "/" + postId + "/exclude", null).statusCode())
                .isEqualTo(204);
        assertThat(json.readTree(send("GET", PATH, null).body()).get("items")).isEmpty();
    }

    @Test
    @DisplayName("出演情報を作ると一覧から消える。登録しても残り続けない")
    void registeringRemovesFromList() throws Exception {
        String body = """
                {"appearanceDate":"2026-09-20","eventName":"『手動登録』",
                 "venueName":null,"performanceStartTime":"18:00:00","performanceEndTime":null,
                 "merchStartTime":null,"merchEndTime":null,"ticketUrl":null,
                 "sourceUrl":"https://x.com/a/status/1","ingestedPostId":%d}
                """.formatted(postId);
        assertThat(send("POST", "/api/admin/appearances", body).statusCode()).isEqualTo(201);
        assertThat(json.readTree(send("GET", PATH, null).body()).get("items")).isEmpty();
    }

    @Test
    @DisplayName("対象外にした投稿は出演情報の抽出元に指定できない")
    void excludedPostCannotBeUsed() throws Exception {
        send("POST", PATH + "/" + postId + "/exclude", null);
        String body = """
                {"appearanceDate":"2026-09-20","eventName":"『手動登録』",
                 "venueName":null,"performanceStartTime":"18:00:00","performanceEndTime":null,
                 "merchStartTime":null,"merchEndTime":null,"ticketUrl":null,
                 "sourceUrl":"https://x.com/a/status/1","ingestedPostId":%d}
                """.formatted(postId);
        assertThat(send("POST", "/api/admin/appearances", body).statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("存在しない投稿を指定すると 400")
    void unknownPostIsBadRequest() throws Exception {
        String body = """
                {"appearanceDate":"2026-09-20","eventName":"『手動登録』",
                 "venueName":null,"performanceStartTime":"18:00:00","performanceEndTime":null,
                 "merchStartTime":null,"merchEndTime":null,"ticketUrl":null,
                 "sourceUrl":"https://x.com/a/status/1","ingestedPostId":999999}
                """;
        assertThat(send("POST", "/api/admin/appearances", body).statusCode()).isEqualTo(400);
    }
}
