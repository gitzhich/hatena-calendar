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
    // わざと DB と違う値を入れる。投稿 URL がこちらに引きずられたら退行
    "X_SOURCE_USERNAME=this-env-var-must-not-be-used"
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
                    "INSERT INTO source_account (username, x_user_id)"
                    + " VALUES ('xinxin_official', 1907616831361396737)")
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
                .as("ハンドルの正本は source_account の行。環境変数ではない")
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
    @DisplayName("編集では ingestedPostId を差し替えられない（docs/api.md「編集」）")
    void updateCannotRepointTheIngestedPost() throws Exception {
        long id = json.readTree(send("POST", "/api/admin/appearances",
                appearancePayload(postId)).body()).get("id").asLong();

        /*
         * **空欄が埋まる編集で試す。** 全項目を null のまま送ると、補完の経路を
         * 通してしまう実装でも何も起きず、この検査が素通りする（変異テストで確認）。
         */
        assertThat(send("PUT", "/api/admin/appearances/" + id,
                appearancePayload(999999L, "東京・テスト会場")).statusCode()).isEqualTo(200);
        // null を送っても消えない
        assertThat(send("PUT", "/api/admin/appearances/" + id,
                appearancePayload(null, "東京・テスト会場")).statusCode()).isEqualTo(200);

        JsonNode after = json.readTree(
                send("GET", "/api/admin/appearances/" + id, null).body());
        assertThat(after.get("venueName").asString())
                .as("前提の確認：編集そのものは効いている")
                .isEqualTo("東京・テスト会場");
        assertThat(after.get("ingestedPostId").asLong())
                .as("ingestedPostId は sourceUrl と同じ投稿を指す導出値。編集で選ぶ値ではない")
                .isEqualTo(postId);
    }

    @Test
    @DisplayName("出演情報を削除しても未処理一覧に戻らない（docs/data-model.md「削除と冪等性」）")
    void deletingDoesNotReopenThePost() throws Exception {
        long id = json.readTree(send("POST", "/api/admin/appearances",
                appearancePayload(postId)).body()).get("id").asLong();
        assertThat(json.readTree(send("GET", PATH, null).body()).get("items")).isEmpty();

        assertThat(send("DELETE", "/api/admin/appearances/" + id, null).statusCode())
                .isEqualTo(204);

        assertThat(json.readTree(send("GET", PATH, null).body()).get("items"))
                .as("戻すと、複数枠のうち 1 枠を消しただけで未処理として現れる条件が生まれる")
                .isEmpty();
        assertThat(status(postId)).isEqualTo("REGISTERED");
    }

    private String status(long id) {
        return tx.execute(s -> (String) em.createNativeQuery(
                "SELECT status FROM ingested_post WHERE id = ?")
                .setParameter(1, id).getSingleResult());
    }

    private static String appearancePayload(Long ingestedPostId) {
        return appearancePayload(ingestedPostId, null);
    }

    private static String appearancePayload(Long ingestedPostId, String venueName) {
        return """
                {"appearanceDate":"2026-09-20","eventName":"『手動登録』",
                 "venueName":%s,"performanceStartTime":"18:00:00","performanceEndTime":null,
                 "merchStartTime":null,"merchEndTime":null,"ticketUrl":null,
                 "sourceUrl":"https://x.com/a/status/1","ingestedPostId":%s}
                """.formatted(venueName == null ? "null" : "\"" + venueName + "\"",
                        ingestedPostId == null ? "null" : ingestedPostId.toString());
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
