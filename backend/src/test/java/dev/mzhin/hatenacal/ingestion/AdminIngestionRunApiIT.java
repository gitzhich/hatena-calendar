package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mzhin.hatenacal.config.ApiKeyFilter;
import dev.mzhin.hatenacal.support.MovableClock;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 取り込み履歴（docs/api.md 第 5.7 節、FR-42 / NFR-04 / NFR-09）。
 *
 * <p>時計を固定する。当月の集計は「今がいつか」で答えが変わるため、
 * 実時刻のままだと月をまたいだ日にだけ落ちるテストになる。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "INTERNAL_API_KEY=public-key",
    "INTERNAL_ADMIN_API_KEY=admin-key",
    // 暦月と一致しない値を使う。1 のままだと請求サイクルで切っているのか
    // 暦月で切っているのか区別できない（NFR-04）
    "x.billing-cycle-start-day=2"
})
@Import(AdminIngestionRunApiIT.MovableClockConfig.class)
class AdminIngestionRunApiIT {

    private static final String ADMIN_KEY = "admin-key";
    private static final String PATH = "/api/admin/ingestion-runs";

    /** JST では 2026-09-15 09:00。当月は 2026 年 9 月。 */
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    @TestConfiguration
    static class MovableClockConfig {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock(NOW);
        }
    }

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
        tx.executeWithoutResult(s ->
                em.createNativeQuery("DELETE FROM ingestion_run").executeUpdate());
    }

    /** 実行記録を 1 件入れる。{@code startedAt} は ISO-8601（オフセット付き）。 */
    private void insertRun(String startedAt, String status, int resources, String errorSummary) {
        insertRun(startedAt, status, resources, errorSummary, false);
    }

    private void insertRun(String startedAt, String status, int resources,
            String errorSummary, boolean truncated) {
        tx.executeWithoutResult(s -> em.createNativeQuery("""
                INSERT INTO ingestion_run
                    (started_at, finished_at, status, fetched_resource_count,
                     new_appearance_count, truncated, error_summary)
                VALUES (?, ?, ?, ?, 0, ?, ?)
                """)
                .setParameter(1, OffsetDateTime.parse(startedAt))
                .setParameter(2, OffsetDateTime.parse(startedAt).plusSeconds(3))
                .setParameter(3, status)
                .setParameter(4, resources)
                .setParameter(5, truncated)
                .setParameter(6, errorSummary)
                .executeUpdate());
    }

    private void insertFailures(int count) {
        for (int i = 0; i < count; i++) {
            insertRun("2026-09-%02dT01:00:00Z".formatted(i + 1), "FAILED", 0, "取得に失敗");
        }
    }

    private JsonNode get(String query) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + PATH + query))
                .header(ApiKeyFilter.ADMIN_HEADER, ADMIN_KEY)
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    @Test
    @DisplayName("新しい順に返す。実行ごとの記録がそのまま読める（FR-42）")
    void listsNewestFirst() throws Exception {
        insertRun("2026-09-10T01:00:00Z", "SUCCESS", 4, null);
        insertRun("2026-09-12T01:00:00Z", "FAILED", 0, "429 が続いたため打ち切り");

        JsonNode body = get("");
        JsonNode items = body.get("items");

        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("status").asString()).isEqualTo("FAILED");
        assertThat(items.get(0).get("errorSummary").asString())
                .isEqualTo("429 が続いたため打ち切り");
        assertThat(items.get(1).get("status").asString()).isEqualTo("SUCCESS");
        assertThat(items.get(1).get("fetchedResourceCount").asInt()).isEqualTo(4);
        assertThat(body.get("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("合計は請求サイクルで切る。暦月で切ると起点前の分が混じる")
    void currentCycleIsBoundedByTheBillingCycle() throws Exception {
        // JST 9/1 23:00。起点（9/2）より前なので数えない。
        // 暦月で切るとここが入ってしまう
        insertRun("2026-09-01T14:00:00Z", "SUCCESS", 100, null);
        // JST 9/2 00:30。サイクルに入る。UTC で切るとここが落ちる
        insertRun("2026-09-01T15:30:00Z", "SUCCESS", 7, null);
        insertRun("2026-09-10T01:00:00Z", "SUCCESS", 4, null);

        assertThat(get("").get("currentCycleResourceCount").asInt())
                .as("100 が入れば暦月で切っており、4 なら JST の起点 9 時間分を落としている")
                .isEqualTo(11);
    }

    @Test
    @DisplayName("集計期間の開始を UTC で返す。何を合計した値かが画面から分かる")
    void reportsTheCycleStart() throws Exception {
        assertThat(get("").get("cycleStartAt").asString())
                .as("JST 2026-09-02 00:00 は UTC では 2026-09-01T15:00")
                .startsWith("2026-09-01T15:00");
    }

    @Test
    @DisplayName("打ち切った実行を truncated で返す（NFR-09 / ADR-0020）")
    void reportsTruncatedRuns() throws Exception {
        insertRun("2026-09-10T01:00:00Z", "SUCCESS", 1000, null, true);
        insertRun("2026-09-11T01:00:00Z", "SUCCESS", 4, null);

        JsonNode items = get("").get("items");

        assertThat(items.get(0).get("truncated").asBoolean())
                .as("新しい順。打ち切っていない実行")
                .isFalse();
        assertThat(items.get(1).get("truncated").asBoolean())
                .as("status は SUCCESS のままなので、これが無いと取りこぼしが画面に出ない")
                .isTrue();
        assertThat(items.get(1).get("status").asString()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("実行記録が無ければ合計は 0。null を返さない")
    void emptyHistoryReportsZero() throws Exception {
        JsonNode body = get("");

        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("currentCycleResourceCount").asInt()).isZero();
        assertThat(body.get("consecutiveFailureCount").asInt()).isZero();
        assertThat(body.get("halted").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("連続失敗がしきい値に達すると halted で返る（NFR-09）")
    void reportsHaltedAfterConsecutiveFailures() throws Exception {
        insertFailures(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES);

        JsonNode body = get("");

        assertThat(body.get("consecutiveFailureCount").asInt())
                .isEqualTo(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES);
        assertThat(body.get("halted").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("しきい値に 1 件足りなければ halted にしない。失敗数は数える")
    void reportsFailureCountBeforeHalting() throws Exception {
        insertFailures(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES - 1);

        JsonNode body = get("");

        assertThat(body.get("consecutiveFailureCount").asInt())
                .isEqualTo(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES - 1);
        assertThat(body.get("halted").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("CANCELLED は連続失敗を切る。警告が消える（runbook 第 9 章）")
    void cancelledRunBreaksTheFailureStreak() throws Exception {
        insertFailures(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES);
        // 管理者が最新の失敗を確認済みにした状態（記録は消さない）
        insertRun("2026-09-20T01:00:00Z", "CANCELLED", 0, "取得に失敗");

        JsonNode body = get("");

        assertThat(body.get("consecutiveFailureCount").asInt())
                .as("先頭が FAILED でなくなれば連続は 0")
                .isZero();
        assertThat(body.get("halted").asBoolean()).isFalse();
        assertThat(body.get("items").get(0).get("status").asString())
                .as("失敗した事実は残す。画面に出せること")
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("警告の判定は表示中のページに引きずられない")
    void haltedIsJudgedFromLatestRunsNotTheShownPage() throws Exception {
        insertFailures(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES);

        // 2 ページ目には失敗が 0 件しか載らないが、止まっている事実は変わらない
        JsonNode body = get("?page=1&size=" + IngestionHaltRule.MAX_CONSECUTIVE_FAILURES);

        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("halted").asBoolean()).isTrue();
        assertThat(body.get("consecutiveFailureCount").asInt())
                .isEqualTo(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES);
    }

    @Test
    @DisplayName("日時は UTC で返す（docs/api.md 第 5.7 節）")
    void timestampsAreUtc() throws Exception {
        insertRun("2026-09-10T01:00:00Z", "SUCCESS", 4, null);

        JsonNode item = get("").get("items").get(0);

        assertThat(OffsetDateTime.parse(item.get("startedAt").asString()).getOffset())
                .isEqualTo(ZoneOffset.UTC);
        assertThat(OffsetDateTime.parse(item.get("startedAt").asString()).toInstant())
                .isEqualTo(Instant.parse("2026-09-10T01:00:00Z"));
    }

    @Test
    @DisplayName("公開キーでは通らない。管理 API は管理キーだけ（ADR-0010）")
    void publicKeyIsRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + PATH))
                .header(ApiKeyFilter.PUBLIC_HEADER, "public-key")
                .GET().build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("更新系のメソッドは受け付けない。打ち切りからの復帰は手作業（FR-43）")
    void mutatingMethodsAreNotExposed() throws Exception {
        for (String method : new String[] {"POST", "PUT", "DELETE"}) {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + PATH))
                    .header(ApiKeyFilter.ADMIN_HEADER, ADMIN_KEY)
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();

            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .as("%s が通ると、原因を確認せずに再開できてしまう", method)
                    .isNotIn(200, 201, 204);
        }
    }
}
