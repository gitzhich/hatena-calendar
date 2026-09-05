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
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 公開 API の契約（docs/api.md「期間内の出演情報一覧」）を通しで確かめる。
 *
 * <p>JDK の HttpClient を直に使う。フレームワークの変換を挟まず、
 * <b>ワイヤ上に実際に何が出るか</b>を検証するため。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "INTERNAL_API_KEY=test-public-key")
class PublicAppearanceApiIT {

    private static final String KEY = "test-public-key";
    private static final String SEPT = "/api/public/appearances?from=2026-09-01&to=2026-09-30";

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    // HTTP は別スレッドで処理されるため、テスト管理のトランザクションでは
    // サーバから見えない。コミットしてから叩き、後で消す。
    @BeforeEach
    void seed() {
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM appearance").executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO appearance (appearance_date, event_name, event_key, venue_name,
                        performance_start_time, performance_end_time,
                        merch_start_time, merch_end_time, ticket_url, source_url, source_type)
                    VALUES (?, ?, 'lonliumprelonelykids', ?, ?, ?, ?, ?, ?, ?, 'MANUAL')
                """)
                    .setParameter(1, LocalDate.of(2026, 9, 16))
                    .setParameter(2, "lonlium pre.『LONELY KIDS』")
                    .setParameter(3, "愛知・大須RADHALL")
                    .setParameter(4, LocalTime.of(19, 50))
                    .setParameter(5, LocalTime.of(20, 15))
                    .setParameter(6, LocalTime.of(21, 25))
                    .setParameter(7, LocalTime.of(22, 35))
                    .setParameter(8, "https://livepocket.jp/e/lk-nagoya0916")
                    .setParameter(9, "https://x.com/a/status/1")
                    .executeUpdate();
        });
    }

    @AfterEach
    void cleanup() {
        tx.executeWithoutResult(
                status -> em.createNativeQuery("DELETE FROM appearance").executeUpdate());
    }

    private HttpResponse<String> send(String path, String apiKey, String method, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path));
        if (apiKey != null) {
            b.header(ApiKeyFilter.PUBLIC_HEADER, apiKey);
        }
        if ("POST".equals(method)) {
            b.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.GET();
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String apiKey) throws Exception {
        return send(path, apiKey, "GET", null);
    }

    private JsonNode first(String path) throws Exception {
        return json.readTree(get(path, KEY).body()).get("appearances").get(0);
    }

    @Test
    @DisplayName("API キーなしは 403。デフォルト拒否が効いている（NFR-03）")
    void deniedWithoutKey() throws Exception {
        assertThat(get(SEPT, null).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("誤った API キーは 403")
    void deniedWithWrongKey() throws Exception {
        assertThat(get(SEPT, "wrong-key").statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("日付と時刻にオフセットが付かない（docs/api.md「日付と時刻」）")
    void datesCarryNoOffset() throws Exception {
        assertThat(get(SEPT, KEY).statusCode()).isEqualTo(200);
        JsonNode item = first(SEPT);
        assertThat(item.get("appearanceDate").asString()).isEqualTo("2026-09-16");
        assertThat(item.get("performanceStartTime").asString()).isEqualTo("19:50:00");
        assertThat(item.get("performanceEndTime").asString()).isEqualTo("20:15:00");
        assertThat(item.get("merchStartTime").asString()).isEqualTo("21:25:00");
        assertThat(item.get("merchEndTime").asString()).isEqualTo("22:35:00");
    }

    @Test
    @DisplayName("イベント名は告知の原文をそのまま返す（ADR-0006）")
    void eventNameIsRaw() throws Exception {
        JsonNode item = first(SEPT);
        assertThat(item.get("eventName").asString()).isEqualTo("lonlium pre.『LONELY KIDS』");
        assertThat(item.get("venueName").asString()).isEqualTo("愛知・大須RADHALL");
    }

    @Test
    @DisplayName("内部項目を返さない。eventKey / sourceType / createdAt は公開しない")
    void hidesInternalFields() throws Exception {
        JsonNode item = first(SEPT);
        assertThat(item.has("eventKey")).isFalse();
        assertThat(item.has("sourceType")).isFalse();
        assertThat(item.has("createdAt")).isFalse();
        assertThat(item.has("ingestedPostId")).isFalse();
    }

    @Test
    @DisplayName("該当がなければ空配列。404 にしない")
    void emptyArrayNotNotFound() throws Exception {
        HttpResponse<String> res =
                get("/api/public/appearances?from=2026-10-01&to=2026-10-31", KEY);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(json.readTree(res.body()).get("appearances")).isEmpty();
    }

    @Test
    @DisplayName("範囲外の年月は 400（ADR-0014）")
    void outOfRangeIsBadRequest() throws Exception {
        assertThat(get("/api/public/appearances?from=9999-12-01&to=9999-12-31", KEY)
                .statusCode()).isEqualTo(400);
        assertThat(get("/api/public/appearances?from=1000-01-01&to=1000-01-31", KEY)
                .statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("from > to と 62 日超は 400")
    void invalidSpanIsBadRequest() throws Exception {
        assertThat(get("/api/public/appearances?from=2026-09-30&to=2026-09-01", KEY)
                .statusCode()).isEqualTo(400);
        assertThat(get("/api/public/appearances?from=2026-09-01&to=2026-11-05", KEY)
                .statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("必須パラメータの欠落は 400（docs/api.md「エラー」）")
    void missingParameterIsBadRequest() throws Exception {
        assertThat(get("/api/public/appearances?to=2026-09-30", KEY).statusCode())
                .isEqualTo(400);
        assertThat(get("/api/public/appearances?from=2026-09-01", KEY).statusCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("パラメータの形式不正は 400。500 にしない（docs/api.md「エラー」）")
    void malformedParameterIsBadRequest() throws Exception {
        assertThat(get("/api/public/appearances?from=abc&to=2026-09-30", KEY).statusCode())
                .isEqualTo(400);
        assertThat(get("/api/public/appearances?from=2026-09-01&to=2026-13-99", KEY).statusCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("エラー応答に内部構造を含めない（NFR-03）")
    void errorHidesInternals() throws Exception {
        assertThat(get("/api/public/appearances?from=9999-12-01&to=9999-12-31", KEY).body())
                .doesNotContain("dev.mzhin", "SELECT", "Exception", "\tat ");
        // 形式不正では変換先の型名とメソッドのシグネチャが漏れやすい
        assertThat(get("/api/public/appearances?from=abc&to=2026-09-30", KEY).body())
                .doesNotContain("dev.mzhin", "LocalDate", "java.", "Exception", "\tat ");
    }

    @Test
    @DisplayName("公開 API は GET のみ。POST は通らない（NFR-03）")
    void postIsNotAllowed() throws Exception {
        assertThat(send("/api/public/appearances", KEY, "POST", "{}").statusCode())
                .isNotEqualTo(200);
    }
}
