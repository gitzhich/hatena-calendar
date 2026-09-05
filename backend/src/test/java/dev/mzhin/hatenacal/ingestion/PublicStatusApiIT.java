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

/**
 * データの状態 API の契約（docs/api.md「データの状態」、FR-08）を通しで確かめる。
 *
 * <p>JDK の HttpClient を直に使う。フレームワークの変換を挟まず、
 * <b>ワイヤ上に実際に何が出るか</b>を検証するため。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "INTERNAL_API_KEY=test-public-key")
class PublicStatusApiIT {

    private static final String KEY = "test-public-key";
    private static final String PATH = "/api/public/status";

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    // HTTP は別スレッドで処理されるため、テスト管理のトランザクションではサーバから見えない。
    // コミットしてから叩く。
    @BeforeEach
    void clean() {
        tx.executeWithoutResult(
                status -> em.createNativeQuery("DELETE FROM ingestion_run").executeUpdate());
    }

    private void insertRun(IngestionRunStatus status, OffsetDateTime finishedAt) {
        tx.executeWithoutResult(ts -> em.createNativeQuery("""
                INSERT INTO ingestion_run (started_at, finished_at, status,
                    fetched_resource_count, new_appearance_count, error_summary)
                VALUES (?, ?, ?, 3, 1, ?)
                """)
                .setParameter(1, finishedAt.minusSeconds(5))
                .setParameter(2, finishedAt)
                .setParameter(3, status.name())
                .setParameter(4, status == IngestionRunStatus.FAILED ? "内部の失敗理由" : null)
                .executeUpdate());
    }

    private HttpResponse<String> send(String apiKey, String method)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + PATH));
        if (apiKey != null) {
            b.header(ApiKeyFilter.PUBLIC_HEADER, apiKey);
        }
        if ("POST".equals(method)) {
            b.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"));
        } else {
            b.GET();
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body() throws Exception {
        HttpResponse<String> res = send(KEY, "GET");
        assertThat(res.statusCode()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private static OffsetDateTime hoursAgo(long hours) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
    }

    @Test
    @DisplayName("API キーなしは 403。デフォルト拒否が効いている（NFR-03）")
    void deniedWithoutKey() throws Exception {
        assertThat(send(null, "GET").statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("誤った API キーは 403")
    void deniedWithWrongKey() throws Exception {
        assertThat(send("wrong-key", "GET").statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("一度も成功していなければ null。stale にはしない")
    void neverSucceeded() throws Exception {
        JsonNode b = body();
        assertThat(b.get("lastSuccessfulIngestionAt").isNull()).isTrue();
        assertThat(b.get("stale").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("日時は UTC で返す。JST への変換は表示側の責務（docs/api.md「データの状態」）")
    void returnsUtc() throws Exception {
        insertRun(IngestionRunStatus.SUCCESS,
                OffsetDateTime.of(2026, 8, 28, 1, 0, 0, 0, ZoneOffset.UTC));
        assertThat(body().get("lastSuccessfulIngestionAt").asString())
                .isEqualTo("2026-08-28T01:00:00Z");
    }

    @Test
    @DisplayName("保存時のオフセットに関わらず UTC 表記になる")
    void normalizesOffsetToUtc() throws Exception {
        // JST の 10:00 は UTC の 01:00。同じ瞬間を指す
        insertRun(IngestionRunStatus.SUCCESS,
                OffsetDateTime.of(2026, 8, 28, 10, 0, 0, 0, ZoneOffset.ofHours(9)));
        assertThat(body().get("lastSuccessfulIngestionAt").asString())
                .isEqualTo("2026-08-28T01:00:00Z");
    }

    @Test
    @DisplayName("最近成功していれば stale ではない")
    void recentSuccessIsFresh() throws Exception {
        insertRun(IngestionRunStatus.SUCCESS, hoursAgo(1));
        assertThat(body().get("stale").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("24 時間以上成功していなければ stale（FR-08）")
    void oldSuccessIsStale() throws Exception {
        insertRun(IngestionRunStatus.SUCCESS, hoursAgo(25));
        assertThat(body().get("stale").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("失敗と実行中は最終更新日時にならない。「成功した」日時を出す")
    void ignoresFailedAndRunning() throws Exception {
        insertRun(IngestionRunStatus.SUCCESS, hoursAgo(30));
        insertRun(IngestionRunStatus.FAILED, hoursAgo(1));
        // RUNNING は finished_at を持たない
        tx.executeWithoutResult(ts -> em.createNativeQuery("""
                INSERT INTO ingestion_run (started_at, status) VALUES (?, 'RUNNING')
                """).setParameter(1, hoursAgo(0)).executeUpdate());

        JsonNode b = body();
        assertThat(b.get("lastSuccessfulIngestionAt").isNull())
                .as("SUCCESS の行があるのに null になった")
                .isFalse();
        assertThat(b.get("stale").asBoolean())
                .as("直近の失敗を成功として拾うと、止まっているのに新鮮に見える")
                .isTrue();
    }

    @Test
    @DisplayName("成功が複数あれば最新を返す")
    void picksLatestSuccess() throws Exception {
        insertRun(IngestionRunStatus.SUCCESS, hoursAgo(30));
        insertRun(IngestionRunStatus.SUCCESS, hoursAgo(2));
        assertThat(body().get("stale").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("運用の内部情報を公開しない（NFR-03）")
    void hidesOperationalDetails() throws Exception {
        insertRun(IngestionRunStatus.FAILED, hoursAgo(1));
        insertRun(IngestionRunStatus.SUCCESS, hoursAgo(2));
        String raw = send(KEY, "GET").body();
        assertThat(raw).doesNotContain("errorSummary", "内部の失敗理由",
                "fetchedResourceCount", "newAppearanceCount", "startedAt", "status\":");
    }

    @Test
    @DisplayName("公開 API は GET のみ。POST は通らない（NFR-03）")
    void postIsNotAllowed() throws Exception {
        assertThat(send(KEY, "POST").statusCode()).isNotEqualTo(200);
    }
}
