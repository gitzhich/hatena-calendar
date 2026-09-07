package dev.mzhin.hatenacal.appearance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mzhin.hatenacal.config.ApiKeyFilter;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 管理 API の契約（docs/api.md「管理 API」）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "INTERNAL_API_KEY=public-key",
    "INTERNAL_ADMIN_API_KEY=admin-key"
})
class AdminApiIT {

    private static final String PUBLIC_KEY = "public-key";
    private static final String ADMIN_KEY = "admin-key";
    private static final String PATH = "/api/admin/appearances";

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    @AfterEach
    void clean() {
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM appearance").executeUpdate();
            em.createNativeQuery("DELETE FROM ingested_post").executeUpdate();
            em.createNativeQuery("DELETE FROM source_account").executeUpdate();
        });
    }

    private HttpResponse<String> send(String method, String path, String key, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path));
        if (key != null) {
            b.header(key.equals(PUBLIC_KEY) ? ApiKeyFilter.PUBLIC_HEADER
                    : ApiKeyFilter.ADMIN_HEADER, key);
        }
        b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String payload(String date, String name, String start) {
        return """
                {"appearanceDate":"%s","eventName":"%s","venueName":"愛知・テスト会場",
                 "performanceStartTime":%s,"performanceEndTime":null,
                 "merchStartTime":null,"merchEndTime":null,"ticketUrl":null,
                 "sourceUrl":"https://x.com/a/status/1","ingestedPostId":null}
                """.formatted(date, name, start == null ? "null" : "\"" + start + "\"");
    }

    // ==================================================================

    @Nested
    @DisplayName("並び順（docs/api.md「出演情報の一覧と個別取得（点検用）」）")
    class SortOrder {

        private void create(String date, String name, String start) throws Exception {
            assertThat(send("POST", PATH, ADMIN_KEY, payload(date, name, start)).statusCode())
                    .isEqualTo(201);
        }

        private List<String> namesOf(String query) throws Exception {
            JsonNode items = json.readTree(send("GET", PATH + query, ADMIN_KEY, null).body())
                    .get("items");
            List<String> names = new ArrayList<>();
            items.forEach(item -> names.add(item.get("eventName").asString()));
            return names;
        }

        /** 登録の順序と公演日の順序をわざと逆にする。既定が登録順のままだと落ちる。 */
        private void createReversed() throws Exception {
            create("2026-09-20", "『後の公演』", "18:00:00");
            create("2026-09-10", "『前の公演』", "18:00:00");
        }

        @Test
        @DisplayName("既定は公演日時の降順。登録順ではない")
        void defaultIsAppearanceDateDesc() throws Exception {
            createReversed();
            assertThat(namesOf("")).containsExactly("『後の公演』", "『前の公演』");
        }

        @Test
        @DisplayName("DATE_ASC は公演日時の昇順")
        void ascending() throws Exception {
            createReversed();
            assertThat(namesOf("?sort=DATE_ASC")).containsExactly("『前の公演』", "『後の公演』");
        }

        @Test
        @DisplayName("CREATED_DESC は登録の新しい順")
        void createdDesc() throws Exception {
            createReversed();
            assertThat(namesOf("?sort=CREATED_DESC")).containsExactly("『前の公演』", "『後の公演』");
        }

        @Test
        @DisplayName("知らない値は既定に倒す。400 にしない")
        void unknownSortFallsBack() throws Exception {
            createReversed();
            HttpResponse<String> res = send("GET", PATH + "?sort=NOPE", ADMIN_KEY, null);
            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(namesOf("?sort=NOPE")).containsExactly("『後の公演』", "『前の公演』");
        }

        @Test
        @DisplayName("時刻未定は昇順でも降順でも最後に置く")
        void nullStartTimeAlwaysLast() throws Exception {
            create("2026-09-20", "『時刻あり』", "18:00:00");
            create("2026-09-20", "『時刻未定』", null);

            assertThat(namesOf("?sort=DATE_DESC"))
                    .as("降順で先頭に来ると、同じ行が向きを変えるたびに端から端へ飛ぶ")
                    .containsExactly("『時刻あり』", "『時刻未定』");
            assertThat(namesOf("?sort=DATE_ASC"))
                    .containsExactly("『時刻あり』", "『時刻未定』");
        }
    }

    @Nested
    @DisplayName("キーの分離（ADR-0010）")
    class KeySeparation {

        @Test
        @DisplayName("公開キーで管理 API を呼ぶと 403。これが分離の目的")
        void publicKeyCannotReachAdminApi() throws Exception {
            assertThat(send("GET", PATH, PUBLIC_KEY, null).statusCode()).isEqualTo(403);
            assertThat(send("POST", PATH, PUBLIC_KEY,
                    payload("2026-09-20", "テスト", "18:00:00")).statusCode()).isEqualTo(403);
        }

        @Test
        @DisplayName("キーなしは 403")
        void noKeyIsForbidden() throws Exception {
            assertThat(send("GET", PATH, null, null).statusCode()).isEqualTo(403);
        }

        @Test
        @DisplayName("管理キーなら通る")
        void adminKeyWorks() throws Exception {
            assertThat(send("GET", PATH, ADMIN_KEY, null).statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("管理キーは公開 API も通せる。逆は通らない")
        void adminKeyAlsoReachesPublicApi() throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/api/public/appearances?from=2026-09-01&to=2026-09-30"))
                    .header(ApiKeyFilter.ADMIN_HEADER, ADMIN_KEY).GET().build();
            assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("内部 API も管理キーだけが通る")
        void internalAuthRequiresAdminKey() throws Exception {
            assertThat(send("POST", "/internal/auth", PUBLIC_KEY, "{\"password\":\"x\"}")
                    .statusCode()).isEqualTo(403);
            assertThat(send("POST", "/internal/auth", ADMIN_KEY, "{\"password\":\"x\"}")
                    .statusCode()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("手動登録（FR-21）")
    class Create {

        @Test
        @DisplayName("201 で作成され、event_key がサーバ側で生成される")
        void createsWithGeneratedEventKey() throws Exception {
            HttpResponse<String> res = send("POST", PATH, ADMIN_KEY,
                    payload("2026-09-20", "『SAMPLE FES』", "18:00:00"));
            assertThat(res.statusCode()).isEqualTo(201);

            JsonNode body = json.readTree(res.body());
            assertThat(body.get("eventKey").asString()).isEqualTo("samplefes");
            assertThat(body.get("sourceType").asString()).isEqualTo("MANUAL");
        }

        @Test
        @DisplayName("同じ日・同じイベント・同じ開始時刻は 409")
        void duplicateIsConflict() throws Exception {
            send("POST", PATH, ADMIN_KEY, payload("2026-09-20", "『FES』", "18:00:00"));
            assertThat(send("POST", PATH, ADMIN_KEY,
                    payload("2026-09-20", "『FES』", "18:00:00")).statusCode()).isEqualTo(409);
        }

        @Test
        @DisplayName("開始時刻が違えば同じ日・同じイベントでも登録できる（ADR-0012）")
        void differentStartTimeIsAllowed() throws Exception {
            assertThat(send("POST", PATH, ADMIN_KEY,
                    payload("2026-08-25", "『DERAX JAM』", "16:35:00")).statusCode())
                    .isEqualTo(201);
            assertThat(send("POST", PATH, ADMIN_KEY,
                    payload("2026-08-25", "『DERAX JAM』", "19:50:00")).statusCode())
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("開始時刻なしは 1 日 1 イベントにつき 1 件だけ")
        void nullStartTimeIsUniquePerEvent() throws Exception {
            assertThat(send("POST", PATH, ADMIN_KEY,
                    payload("2026-09-20", "『FES』", null)).statusCode()).isEqualTo(201);
            assertThat(send("POST", PATH, ADMIN_KEY,
                    payload("2026-09-20", "『FES』", null)).statusCode()).isEqualTo(409);
        }

        @Test
        @DisplayName("表記ゆれは同じイベントとして 409 になる。正規化が効いている")
        void normalizedNameCollides() throws Exception {
            send("POST", PATH, ADMIN_KEY, payload("2026-09-20", "『ORANGE CHEER』", "18:00:00"));
            assertThat(send("POST", PATH, ADMIN_KEY,
                    payload("2026-09-20", "ORANGE CHEER", "18:00:00")).statusCode())
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("sourceUrl が https でなければ 400。根拠のないデータを公開しない")
        void sourceUrlMustBeHttps() throws Exception {
            String body = payload("2026-09-20", "『FES』", "18:00:00")
                    .replace("https://x.com/a/status/1", "http://x.com/a/status/1");
            assertThat(send("POST", PATH, ADMIN_KEY, body).statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("必須項目が欠ければ 400。サーバ側検証を正とする")
        void missingRequiredFieldIsBadRequest() throws Exception {
            assertThat(send("POST", PATH, ADMIN_KEY,
                    "{\"appearanceDate\":\"2026-09-20\",\"sourceUrl\":\"https://x.com/a/1\"}")
                    .statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("終了が開始より前なら 400")
        void reversedTimesAreBadRequest() throws Exception {
            String body = payload("2026-09-20", "『FES』", "20:00:00")
                    .replace("\"performanceEndTime\":null", "\"performanceEndTime\":\"19:00:00\"");
            assertThat(send("POST", PATH, ADMIN_KEY, body).statusCode()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("編集・削除（FR-22 / FR-23）")
    class UpdateDelete {

        private long create(String date, String name, String start) throws Exception {
            return json.readTree(send("POST", PATH, ADMIN_KEY, payload(date, name, start)).body())
                    .get("id").asLong();
        }

        @Test
        @DisplayName("編集すると event_key が再計算される")
        void updateRecalculatesEventKey() throws Exception {
            long id = create("2026-09-20", "『BEFORE』", "18:00:00");
            HttpResponse<String> res = send("PUT", PATH + "/" + id, ADMIN_KEY,
                    payload("2026-09-20", "『AFTER』", "18:00:00"));
            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(json.readTree(res.body()).get("eventKey").asString()).isEqualTo("after");
        }

        @Test
        @DisplayName("自分自身との衝突は 409 にしない")
        void selfIsNotAConflict() throws Exception {
            long id = create("2026-09-20", "『FES』", "18:00:00");
            assertThat(send("PUT", PATH + "/" + id, ADMIN_KEY,
                    payload("2026-09-20", "『FES』", "18:00:00")).statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("他の行と衝突する編集は 409")
        void updateIntoExistingIsConflict() throws Exception {
            create("2026-09-20", "『A』", "18:00:00");
            long second = create("2026-09-20", "『B』", "19:00:00");
            assertThat(send("PUT", PATH + "/" + second, ADMIN_KEY,
                    payload("2026-09-20", "『A』", "18:00:00")).statusCode()).isEqualTo(409);
        }

        @Test
        @DisplayName("削除は 204。存在しない ID は 404")
        void deleteAndNotFound() throws Exception {
            long id = create("2026-09-20", "『FES』", "18:00:00");
            assertThat(send("DELETE", PATH + "/" + id, ADMIN_KEY, null).statusCode())
                    .isEqualTo(204);
            assertThat(send("DELETE", PATH + "/" + id, ADMIN_KEY, null).statusCode())
                    .isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("エラー応答（NFR-03）")
    class ErrorResponses {

        @Test
        @DisplayName("内部構造を漏らさない")
        void hidesInternals() throws Exception {
            String body = send("POST", PATH, ADMIN_KEY, "{}").body();
            assertThat(body).doesNotContain("dev.mzhin", "SELECT", "Exception", "\\tat ");
        }

        @Test
        @DisplayName("壊れたボディは 400。500 にしない（docs/api.md「エラー」）")
        void malformedBodyIsBadRequest() throws Exception {
            assertThat(send("POST", PATH, ADMIN_KEY, "{ not json").statusCode())
                    .isEqualTo(400);
            assertThat(send("POST", PATH, ADMIN_KEY, null).statusCode())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("壊れたボディの応答にも内部構造を含めない（NFR-03）")
        void malformedBodyHidesInternals() throws Exception {
            String body = send("POST", PATH, ADMIN_KEY, "{ not json").body();
            assertThat(body).doesNotContain("dev.mzhin", "JsonParseException", "com.fasterxml",
                    "tools.jackson", "Exception", "\\tat ");
        }

        @Test
        @DisplayName("パス変数の形式不正は 400（docs/api.md「エラー」）")
        void malformedPathVariableIsBadRequest() throws Exception {
            assertThat(send("GET", PATH + "/abc", ADMIN_KEY, null).statusCode())
                    .isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("ページング（docs/api.md「出演情報の一覧と個別取得（点検用）」）")
    class Paging {

        @Test
        @DisplayName("範囲外の page / size は丸める。400 にしない")
        void outOfRangePagingIsClamped() throws Exception {
            send("POST", PATH, ADMIN_KEY, payload("2026-09-20", "『FES』", "18:00:00"));

            HttpResponse<String> low = send("GET", PATH + "?page=-1&size=0", ADMIN_KEY, null);
            assertThat(low.statusCode())
                    .as("点検一覧は値がずれても画面が止まらないほうがよい")
                    .isEqualTo(200);
            assertThat(low.body()).contains("\"page\":0").contains("\"size\":1");

            HttpResponse<String> high = send("GET", PATH + "?size=1000", ADMIN_KEY, null);
            assertThat(high.statusCode()).isEqualTo(200);
            assertThat(high.body())
                    .as("上限を超える size で DB を引かせない")
                    .contains("\"size\":100");
        }

        @Test
        @DisplayName("数として読めない size は 400。丸めるのは範囲外だけ")
        void nonNumericSizeIsBadRequest() throws Exception {
            assertThat(send("GET", PATH + "?size=abc", ADMIN_KEY, null).statusCode())
                    .isEqualTo(400);
        }
    }
}
