package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 取り込みの結合検証（FR-40〜FR-43）。
 *
 * <p><b>実 X API は叩かない</b>（ADR-0002）。記録済みの形のレスポンスを返す
 * スタブに差し替える。DB は実物を使う。日付・時刻の扱いと一意制約が
 * 効いていることまで見たいため。
 */
@SpringBootTest
@TestPropertySource(properties = {
    "X_BEARER_TOKEN=stub-token",
    "X_SOURCE_USERNAME=xinxin_official",
    // スケジューラを止める。テスト中に勝手に走らせない
    "X_INGESTION_ENABLED=false"
})
@Import(IngestionServiceIT.StubConfig.class)
class IngestionServiceIT {

    private static final long X_USER_ID = 1907616831361396737L;

    /** 取得層だけを差し替える。取り込みの筋道は本物を通す。 */
    static final class StubXApiClient implements XApiClient {
        final Deque<Object> responses = new ArrayDeque<>();
        final List<FetchWindow> windows = new ArrayList<>();
        int calls;

        @Override
        public FetchResult fetchPosts(long xUserId, FetchWindow window,
                String paginationToken) {
            calls++;
            windows.add(window);
            Object next = responses.poll();
            if (next == null) {
                return FetchResult.empty();
            }
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (FetchResult) next;
        }

        void reset() {
            responses.clear();
            windows.clear();
            calls = 0;
        }
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubXApiClient stubXApiClient() {
            return new StubXApiClient();
        }
    }

    @Autowired
    private IngestionService service;
    @Autowired
    private StubXApiClient client;
    @Autowired
    private EntityManager em;
    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void reset() {
        client.reset();
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM appearance").executeUpdate();
            em.createNativeQuery("DELETE FROM ingested_post").executeUpdate();
            em.createNativeQuery("DELETE FROM ingestion_run").executeUpdate();
            em.createNativeQuery("DELETE FROM source_account").executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO source_account (username, x_user_id, last_fetched_tweet_id)
                    VALUES ('xinxin_official', ?, 1000)
                    """).setParameter(1, X_USER_ID).executeUpdate();
        });
    }

    // ------------------------------------------------------------ 補助

    private static OffsetDateTime at(int y, int m, int d) {
        return OffsetDateTime.of(y, m, d, 12, 0, 0, 0, ZoneOffset.ofHours(9));
    }

    /** 情報解禁の告知。会場とタイムテーブルの行を差し替えて使う。 */
    private static String announcement(String venue, String timetable) {
        return """
                🔸XINXIN愛知公演情報解禁🔸

                9/16(水)📍愛知・%s
                『テストイベント』

                ⏰OPEN 17:00 / START 17:30
                🔗https://example.com/ticket

                ▪️タイムテーブル
                %s
                """.formatted(venue, timetable);
    }

    private static String announcement(String timetable) {
        return announcement("テスト会場", timetable);
    }

    private static SourcePost post(long id, String body, OffsetDateTime postedAt) {
        return new SourcePost(id, body, null, postedAt);
    }

    private static FetchResult page(String nextToken, SourcePost... posts) {
        return new FetchResult(List.of(posts), nextToken);
    }

    private long count(String table) {
        return tx.execute(s -> ((Number) em
                .createNativeQuery("SELECT count(*) FROM " + table)
                .getSingleResult()).longValue());
    }

    private Object column(String sql) {
        return tx.execute(s -> em.createNativeQuery(sql).getResultList().stream()
                .findFirst().orElse(null));
    }

    private void insertRun(String status, OffsetDateTime startedAt) {
        tx.executeWithoutResult(s -> em.createNativeQuery("""
                INSERT INTO ingestion_run (started_at, status) VALUES (?, ?)
                """).setParameter(1, startedAt).setParameter(2, status).executeUpdate());
    }

    // ------------------------------------------------------------ 取得

    @Test
    @DisplayName("取得済みの位置があれば since_id で差分取得する")
    void usesSinceIdWhenPositionKnown() {
        service.run();

        assertThat(client.windows).singleElement()
                .as("全件取り直しは 24 時間の重複排除を外れて再課金する（FR-40）")
                .isEqualTo(new FetchWindow.Since(1000L));
    }

    @Test
    @DisplayName("取得済みの位置が無ければ、範囲を限定したバックフィルになる")
    void backfillIsBoundedWhenPositionUnknown() {
        tx.executeWithoutResult(s -> em.createNativeQuery(
                "UPDATE source_account SET last_fetched_tweet_id = NULL").executeUpdate());

        service.run();

        assertThat(client.windows).singleElement()
                .as("無制限に遡ると課金が読めない（第 8 章）")
                .isInstanceOf(FetchWindow.From.class);
    }

    @Test
    @DisplayName("取得したリソース数を実行記録に残す（NFR-04）")
    void recordsFetchedResourceCount() {
        client.responses.add(page(null,
                post(2001, "ただのお礼投稿", at(2026, 9, 1)),
                post(2002, "本日のお写真📸", at(2026, 9, 1))));

        service.run();

        assertThat(column("SELECT fetched_resource_count FROM ingestion_run"))
                .as("課金はリクエスト数ではなく返却リソース数に対して発生する")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("ページ数の上限で打ち切り、暴走した課金を防ぐ（第 3.4 節）")
    void stopsAtPageLimit() {
        for (int i = 0; i < 30; i++) {
            client.responses.add(page("next-token",
                    post(3000 + i, "お礼投稿", at(2026, 9, 1))));
        }

        service.run();

        assertThat(client.calls)
                .as("既定のページ上限は 10。次回に持ち越す")
                .isEqualTo(10);
    }

    // ------------------------------------------------------------ 処理順

    @Test
    @DisplayName("投稿を古い順に処理し、後続の告知が空欄を埋める（第 2.1 節）")
    void processesOldestFirstSoLaterPostsFillBlanks() {
        /*
         * API は新しい順に返す。並べ替えていなければ結果が変わるように組む。
         * 2 件は会場が違う。行を「作った」ほうの会場が残り、あとから来た
         * ほうは値のある列を上書きできない。どちらが先に処理されたかが
         * 会場と出典 URL に現れる。
         */
        client.responses.add(page(null,
                post(2100, announcement("新しい投稿の会場",
                        "🎤19:50-20:15 XINXIN出演\n📸20:30-21:00 物販"), at(2026, 9, 2)),
                post(2099, announcement("古い投稿の会場",
                        "🎤19:50-20:15 XINXIN出演"), at(2026, 9, 1))));

        service.run();

        assertThat(count("appearance"))
                .as("同じ公演を指すなら新しい行を作らず空欄を埋める（FR-41）")
                .isEqualTo(1);
        assertThat(column("SELECT venue_name FROM appearance"))
                .as("古い投稿が行を作る。新しい順に処理していたら新しい会場になる")
                .isEqualTo("愛知・古い投稿の会場");
        assertThat(column("SELECT merch_start_time FROM appearance"))
                .as("あとから来た告知が空欄を埋める")
                .isNotNull();
        assertThat(column("SELECT source_url FROM appearance"))
                .as("補完した告知が出典になる（第 7.1 節）")
                .isEqualTo("https://x.com/xinxin_official/status/2100");
    }

    @Test
    @DisplayName("1 投稿の複数枠は出演開始時刻の昇順で処理する（第 7.1 節）")
    void multipleSlotsAreProcessedInAscendingOrder() {
        /*
         * 時刻なしの既存行を「引き継ぐのは最も早い枠」と決まっている。
         * 降順で処理すると 19:50 が引き継いでしまい、同じ入力から違う結果が出る。
         */
        insertManual("", "");
        String body = announcement("🎤19:50-20:15 XINXIN出演\n🎤16:35-16:55 XINXIN出演");
        client.responses.add(page(null, post(2200, body, at(2026, 9, 1))));

        service.run();

        assertThat(count("appearance"))
                .as("同じ日・同じイベントでも開始時刻で区別する（ADR-0012）")
                .isEqualTo(2);
        assertThat(column(
                "SELECT performance_start_time FROM appearance WHERE source_type = 'MANUAL'"))
                .as("既存行を引き継ぐのは最も早い枠。降順なら 19:50 になる")
                .hasToString("16:35");
    }

    // ------------------------------------------------------------ 未処理

    @Test
    @DisplayName("抽出できない投稿は破棄せず未処理として残す（FR-25 / FR-41）")
    void keepsUnparsedPosts() {
        client.responses.add(page(null,
                post(2300, "／\n  本日のお写真📸\n＼\n\n本日もありがとうございました",
                        at(2026, 9, 1))));

        service.run();

        assertThat(count("appearance")).isZero();
        assertThat(column("SELECT status FROM ingested_post")).isEqualTo("UNPARSED");
    }

    @Test
    @DisplayName("同じ投稿を 2 回処理しても出演情報が増えない（冪等性）")
    void reprocessingIsIdempotent() {
        SourcePost p = post(2400, announcement("🎤19:50-20:15 XINXIN出演"), at(2026, 9, 1));
        client.responses.add(page(null, p));
        service.run();
        long first = count("appearance");

        // 取得位置を戻して同じ投稿をもう一度流す
        tx.executeWithoutResult(s -> em.createNativeQuery(
                "UPDATE source_account SET last_fetched_tweet_id = 1000").executeUpdate());
        client.responses.add(page(null, p));
        service.run();

        assertThat(first).isEqualTo(1);
        assertThat(count("appearance"))
                .as("取り込み済みの投稿は飛ばす")
                .isEqualTo(1);
        assertThat(column("SELECT status FROM ingestion_run ORDER BY id DESC"))
                .as("アプリ側で飛ばすこと。tweet_id の UNIQUE 違反で"
                        + "止まっているなら実行が FAILED になる")
                .isEqualTo("SUCCESS");
        assertThat(count("ingested_post")).isEqualTo(1);
    }

    // ------------------------------------------------------------ 取得位置

    @Test
    @DisplayName("取得位置は全件処理後に、取得できた最大 ID で進む（第 2.1 節）")
    void advancesToMaxIdAfterProcessing() {
        client.responses.add(page(null,
                post(2510, "お礼", at(2026, 9, 2)),
                post(2500, "お礼", at(2026, 9, 1))));

        service.run();

        assertThat(column("SELECT last_fetched_tweet_id FROM source_account"))
                .isEqualTo(2510L);
    }

    @Test
    @DisplayName("取得位置は後退しない（第 2.2 節）")
    void neverMovesBackwards() {
        tx.executeWithoutResult(s -> em.createNativeQuery(
                "UPDATE source_account SET last_fetched_tweet_id = 9999").executeUpdate());
        client.responses.add(page(null, post(2600, "お礼", at(2026, 9, 1))));

        service.run();

        assertThat(column("SELECT last_fetched_tweet_id FROM source_account"))
                .as("後退させると翌日に同じ投稿を取り直して再課金する")
                .isEqualTo(9999L);
    }

    @Test
    @DisplayName("失敗したら取得位置を進めない。次回同じ範囲を取り直す")
    void failureDoesNotAdvancePosition() {
        client.responses.add(new XApiException("HTTP 503", 503));

        IngestionService.Result result = service.run();

        assertThat(result).isEqualTo(IngestionService.Result.FAILED);
        assertThat(column("SELECT last_fetched_tweet_id FROM source_account"))
                .isEqualTo(1000L);
        assertThat(column("SELECT status FROM ingestion_run")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("失敗の記録にトークンやスタックトレースを残さない（NFR-03）")
    void errorSummaryIsSafe() {
        client.responses.add(new XApiException("X API の取得に失敗しました（HTTP 401）", 401));

        service.run();

        String summary = (String) column("SELECT error_summary FROM ingestion_run");
        assertThat(summary).isNotNull().doesNotContain("stub-token").doesNotContain("\tat ");
    }

    // ------------------------------------------------------------ 門番

    @Test
    @DisplayName("実行中の記録があれば今回はスキップする（第 10.1 節）")
    void skipsWhileAnotherRunIsActive() {
        insertRun("RUNNING", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        assertThat(service.run()).isEqualTo(IngestionService.Result.ALREADY_RUNNING);
        assertThat(client.calls).isZero();
    }

    @Test
    @DisplayName("閾値を超えた実行中の記録は倒して、今回の実行を続ける（第 10.1 節）")
    void reapsStaleRunAndContinues() {
        insertRun("RUNNING", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30));

        assertThat(service.run())
                .as("落ちた実行の記録が残り続けて取り込みが恒久的に止まるのを防ぐ")
                .isEqualTo(IngestionService.Result.COMPLETED);
        assertThat(count("ingestion_run")).isEqualTo(2);
    }

    @Test
    @DisplayName("連続 10 回失敗したら止まり、自動で再開しない（第 7 章 / FR-43）")
    void haltsAfterConsecutiveFailures() {
        for (int i = 0; i < 10; i++) {
            insertRun("FAILED", OffsetDateTime.now(ZoneOffset.UTC).minusHours(10 - i));
        }

        assertThat(service.run()).isEqualTo(IngestionService.Result.HALTED);
        assertThat(client.calls)
                .as("失敗のたびに同じ範囲を取り直すため、放置すると毎日再課金される")
                .isZero();
    }

    @Test
    @DisplayName("失敗が 9 回なら止まらない")
    void doesNotHaltBelowThreshold() {
        for (int i = 0; i < 9; i++) {
            insertRun("FAILED", OffsetDateTime.now(ZoneOffset.UTC).minusHours(9 - i));
        }

        assertThat(service.run()).isEqualTo(IngestionService.Result.COMPLETED);
    }

    @Test
    @DisplayName("成功が 1 件でも混じっていれば止まらない")
    void oneSuccessBreaksTheFailureStreak() {
        for (int i = 0; i < 9; i++) {
            insertRun("FAILED", OffsetDateTime.now(ZoneOffset.UTC).minusHours(20 - i));
        }
        insertRun("SUCCESS", OffsetDateTime.now(ZoneOffset.UTC).minusHours(5));

        assertThat(service.run()).isEqualTo(IngestionService.Result.COMPLETED);
    }

    // ------------------------------------------------------------ 補完

    /** 手動登録された行を 1 件置く。event_key は EventKey.of("テストイベント")。 */
    private void insertManual(String columns, String values) {
        tx.executeWithoutResult(s -> em.createNativeQuery(("""
                INSERT INTO appearance
                  (appearance_date, event_name, event_key, source_url, source_type%s)
                VALUES (DATE '2026-09-16', 'テストイベント', 'テストイベント',
                        'https://x.com/xinxin_official/status/1', 'MANUAL'%s)
                """).formatted(columns, values)).executeUpdate());
    }

    @Test
    @DisplayName("時刻なしで手動登録された行に、後続の告知が時刻を入れる（第 7.1 節）")
    void fillsTimeIntoManuallyCreatedRow() {
        insertManual("", "");
        client.responses.add(page(null,
                post(2700, announcement("🎤19:50-20:15 XINXIN出演"), at(2026, 9, 1))));

        service.run();

        assertThat(count("appearance"))
                .as("新しい行を作らず、既存の行を埋める")
                .isEqualTo(1);
        assertThat(column("SELECT performance_start_time FROM appearance"))
                .hasToString("19:50");
        assertThat(column("SELECT source_type FROM appearance"))
                .as("作ったのは管理者。空欄が埋まっても MANUAL のまま")
                .isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("値が入っている列は上書きしない。管理者の修正が巻き戻らない（FR-22）")
    void neverOverwritesExistingValues() {
        insertManual(", venue_name, performance_start_time",
                ", '管理者が直した会場', TIME '19:50'");
        client.responses.add(page(null,
                post(2800, announcement("🎤19:50-20:15 XINXIN出演"), at(2026, 9, 1))));

        service.run();

        assertThat(column("SELECT venue_name FROM appearance"))
                .as("告知の会場で上書きされない")
                .isEqualTo("管理者が直した会場");
        assertThat(column("SELECT performance_end_time FROM appearance"))
                .as("空欄だった終了時刻は埋まる")
                .hasToString("20:15");
    }

    @Test
    @DisplayName("補完が起きたら出典 URL をその告知のものへ更新する（第 7.1 節）")
    void completionUpdatesSourceUrl() {
        insertManual("", "");
        client.responses.add(page(null,
                post(2900, announcement("🎤19:50-20:15 XINXIN出演"), at(2026, 9, 1))));

        service.run();

        assertThat(column("SELECT source_url FROM appearance"))
                .as("出演時刻を載せた告知が出典として示されるべき（FR-06）")
                .isEqualTo("https://x.com/xinxin_official/status/2900");
        assertThat(column("SELECT ingested_post_id FROM appearance")).isNotNull();
    }

    @Test
    @DisplayName("埋める空欄が無ければ出典も動かさない")
    void unchangedRowKeepsItsSourceUrl() {
        insertManual(", venue_name, performance_start_time, performance_end_time,"
                + " merch_start_time, merch_end_time, ticket_url",
                ", '会場', TIME '19:50', TIME '20:15', TIME '20:30', TIME '21:00',"
                + " 'https://example.com/t'");
        client.responses.add(page(null,
                post(3100, announcement("🎤19:50-20:15 XINXIN出演"), at(2026, 9, 1))));

        service.run();

        assertThat(column("SELECT source_url FROM appearance"))
                .isEqualTo("https://x.com/xinxin_official/status/1");
    }

    @Test
    @DisplayName("情報源アカウントが無ければ API を叩かずスキップする")
    void skipsWithoutSourceAccount() {
        tx.executeWithoutResult(s ->
                em.createNativeQuery("DELETE FROM source_account").executeUpdate());

        assertThat(service.run()).isEqualTo(IngestionService.Result.NO_SOURCE_ACCOUNT);
        assertThat(client.calls).isZero();
        assertThat(count("ingestion_run"))
                .as("叩いていないので実行記録も作らない")
                .isZero();
    }
}
