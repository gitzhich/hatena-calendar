package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link XApiHttpClient} の検証。
 *
 * <p><b>実 X API は叩かない</b>（ADR-0002 / CLAUDE.md）。実行のたびに課金されるため、
 * JDK 同梱の HTTP サーバをローカルに立て、記録済みのレスポンスを返す。
 * 実サーバを使うのは、ステータスごとのリトライ判断と URL の組み立てが
 * 実際の HTTP 経路を通ることまで確かめたいため。
 */
class XApiHttpClientTest {

    private static final long USER_ID = 1907616831361396737L;

    private HttpServer server;
    private final Deque<Canned> responses = new ArrayDeque<>();
    private final List<String> requestedUris = new ArrayList<>();

    /** 返す予定のレスポンス。 */
    private record Canned(int status, String body, String rateLimitReset) {
        static Canned ok(String body) {
            return new Canned(200, body, null);
        }

        static Canned status(int status) {
            return new Canned(status, "{\"title\":\"error\"}", null);
        }
    }

    /** 待たないスリーパー。呼ばれた待機時間だけ覚える。 */
    private static final class RecordingSleeper implements Sleeper {
        private final List<Duration> waits = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            waits.add(duration);
        }
    }

    private final RecordingSleeper sleeper = new RecordingSleeper();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestedUris.add(exchange.getRequestURI().toString());
        Canned canned = responses.isEmpty()
                ? Canned.ok("{\"meta\":{\"result_count\":0}}")
                : responses.poll();
        byte[] body = canned.body().getBytes(StandardCharsets.UTF_8);
        if (canned.rateLimitReset() != null) {
            exchange.getResponseHeaders().add("x-rate-limit-reset", canned.rateLimitReset());
        }
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(canned.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private XApiHttpClient client() {
        return client("test-token");
    }

    private XApiHttpClient client(String token) {
        XApiProperties props = new XApiProperties(
                token,
                "xinxin_official",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                100, 10, 3,
                Duration.ofSeconds(1), Duration.ofSeconds(60),
                Duration.ofSeconds(5), Duration.ofSeconds(30),
                3);
        return new XApiHttpClient(props, sleeper);
    }

    // ---------------------------------------------------------------- 取得

    @Test
    @DisplayName("投稿を取得し、note_tweet があれば本文にそれを使う")
    void parsesPostsAndPrefersNoteTweet() {
        responses.add(Canned.ok("""
                {"data":[
                  {"id":"2094978787138240644","created_at":"2026-09-02T02:40:40.000Z",
                   "text":"切れた本文… https://t.co/abc",
                   "note_tweet":{"text":"完全な本文。ここに物販の行がある"}},
                  {"id":"2094742095630328210","created_at":"2026-09-01T11:00:08.000Z",
                   "text":"短い投稿"}
                ],"meta":{"result_count":2,"next_token":"7140dibdnow9"}}
                """));

        FetchResult result = client().fetchPosts(USER_ID, new FetchWindow.Since(1L), null);

        assertThat(result.posts()).hasSize(2);
        assertThat(result.posts().get(0).body())
                .as("note_tweet があるときは text を使わない。text は物販の行の途中で切れる")
                .isEqualTo("完全な本文。ここに物販の行がある");
        assertThat(result.posts().get(1).body())
                .as("note_tweet が無ければ text を使う")
                .isEqualTo("短い投稿");
        assertThat(result.posts().get(0).id()).isEqualTo(2094978787138240644L);
        assertThat(result.posts().get(0).createdAt())
                .isEqualTo(OffsetDateTime.of(2026, 9, 2, 2, 40, 40, 0, ZoneOffset.UTC));
        assertThat(result.nextToken()).isEqualTo("7140dibdnow9");
        assertThat(result.hasNextPage()).isTrue();
    }

    @Test
    @DisplayName("課金対象のリソース数は返却された投稿数と一致する")
    void resourceCountMatchesReturnedPosts() {
        responses.add(Canned.ok("""
                {"data":[
                  {"id":"1","created_at":"2026-09-02T00:00:00.000Z","text":"a"},
                  {"id":"2","created_at":"2026-09-02T00:00:00.000Z","text":"b"},
                  {"id":"3","created_at":"2026-09-02T00:00:00.000Z","text":"c"}
                ],"meta":{"result_count":3}}
                """));

        FetchResult result = client().fetchPosts(USER_ID, new FetchWindow.Since(1L), null);

        assertThat(result.resourceCount())
                .as("課金はリクエスト数ではなく返却リソース数に対して発生する")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("新規投稿が無いとき 0 件で正常終了し、課金対象も 0 になる")
    void emptyResponseCostsNothing() {
        responses.add(Canned.ok("{\"meta\":{\"result_count\":0}}"));

        FetchResult result = client().fetchPosts(USER_ID, new FetchWindow.Since(999L), null);

        assertThat(result.posts()).isEmpty();
        assertThat(result.resourceCount()).isZero();
        assertThat(result.hasNextPage()).isFalse();
    }

    // ---------------------------------------------------------------- URL

    @Test
    @DisplayName("差分取得では since_id を必ず渡す")
    void alwaysSendsSinceId() {
        responses.add(Canned.ok("{\"meta\":{\"result_count\":0}}"));

        client().fetchPosts(USER_ID, new FetchWindow.Since(2094065845186318628L), null);

        assertThat(requestedUris).singleElement().satisfies(uri -> {
            assertThat(uri).contains("/2/users/1907616831361396737/tweets");
            assertThat(uri)
                    .as("since_id を渡さないと全件取り直しになり再課金する（FR-40）")
                    .contains("since_id=2094065845186318628");
            assertThat(uri)
                    .as("返信とリツイートを除外した分はそのまま課金削減になる")
                    .contains("exclude=replies,retweets");
            assertThat(uri).contains("max_results=100");
            assertThat(uri)
                    .as("text は途中で切れるため note_tweet が要る")
                    .contains("tweet.fields=created_at,note_tweet");
            assertThat(uri)
                    .as("画像は保持しないので expansions を指定しない。指定すると課金が増える")
                    .doesNotContain("expansions");
        });
    }

    @Test
    @DisplayName("バックフィルでは start_time で範囲を限定する")
    void backfillIsBounded() {
        responses.add(Canned.ok("{\"meta\":{\"result_count\":0}}"));

        client().fetchPosts(USER_ID,
                new FetchWindow.From(OffsetDateTime.of(2026, 6, 2, 0, 0, 0, 0, ZoneOffset.UTC)),
                null);

        assertThat(requestedUris).singleElement().satisfies(uri -> {
            assertThat(uri).contains("start_time=2026-06-02T00:00");
            assertThat(uri).doesNotContain("since_id");
        });
    }

    @Test
    @DisplayName("ページングのトークンを渡す")
    void sendsPaginationToken() {
        responses.add(Canned.ok("{\"meta\":{\"result_count\":0}}"));

        client().fetchPosts(USER_ID, new FetchWindow.Since(1L), "7140dibdnow9c7btwoxjsiso2");

        assertThat(requestedUris).singleElement()
                .satisfies(uri -> assertThat(uri).contains("pagination_token=7140dibdnow9c7btwoxjsiso2"));
    }

    // ---------------------------------------------------------------- リトライ

    @Test
    @DisplayName("429 は指数バックオフでリトライし、回復すれば成功する")
    void retriesOnRateLimit() {
        responses.add(Canned.status(429));
        responses.add(Canned.status(429));
        responses.add(Canned.ok("{\"meta\":{\"result_count\":0}}"));

        FetchResult result = client().fetchPosts(USER_ID, new FetchWindow.Since(1L), null);

        assertThat(result.posts()).isEmpty();
        assertThat(requestedUris).hasSize(3);
        assertThat(sleeper.waits)
                .as("指数バックオフ。同じ間隔で叩き続けない")
                .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("5xx もリトライする")
    void retriesOnServerError() {
        responses.add(Canned.status(503));
        responses.add(Canned.ok("{\"meta\":{\"result_count\":0}}"));

        client().fetchPosts(USER_ID, new FetchWindow.Since(1L), null);

        assertThat(requestedUris).hasSize(2);
    }

    @Test
    @DisplayName("リトライ回数には上限があり、無限に試みない")
    void retriesAreBounded() {
        for (int i = 0; i < 10; i++) {
            responses.add(Canned.status(500));
        }

        assertThatThrownBy(() -> client().fetchPosts(USER_ID, new FetchWindow.Since(1L), null))
                .isInstanceOf(XApiException.class);

        assertThat(requestedUris)
                .as("初回 1 回 + max-retries 3 回。無限リトライは課金が積み上がる（FR-43）")
                .hasSize(4);
    }

    @Test
    @DisplayName("429 で x-rate-limit-reset があれば、その時刻まで待つ")
    void honoursRateLimitReset() {
        long resetIn30s = System.currentTimeMillis() / 1000L + 30;
        responses.add(new Canned(429, "{}", String.valueOf(resetIn30s)));
        responses.add(Canned.ok("{\"meta\":{\"result_count\":0}}"));

        client().fetchPosts(USER_ID, new FetchWindow.Since(1L), null);

        assertThat(sleeper.waits).singleElement().satisfies(w ->
                assertThat(w.getSeconds())
                        .as("サーバが示したリセット時刻に従う。早く叩き直しても弾かれる")
                        .isBetween(25L, 30L));
    }

    @Test
    @DisplayName("待機時間には上限があり、際限なく延びない")
    void backoffIsCapped() {
        long resetInOneDay = System.currentTimeMillis() / 1000L + 86_400;
        responses.add(new Canned(429, "{}", String.valueOf(resetInOneDay)));
        responses.add(Canned.ok("{\"meta\":{\"result_count\":0}}"));

        client().fetchPosts(USER_ID, new FetchWindow.Since(1L), null);

        assertThat(sleeper.waits).containsExactly(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("401 はリトライしない")
    void doesNotRetryUnauthorized() {
        responses.add(Canned.status(401));

        assertThatThrownBy(() -> client().fetchPosts(USER_ID, new FetchWindow.Since(1L), null))
                .isInstanceOf(XApiException.class)
                .hasMessageContaining("401");

        assertThat(requestedUris)
                .as("トークンの失効は再試行しても直らない。叩くだけ無駄に課金しうる")
                .hasSize(1);
        assertThat(sleeper.waits).isEmpty();
    }

    @Test
    @DisplayName("403 と 404 もリトライしない")
    void doesNotRetryForbiddenOrNotFound() {
        for (int status : new int[] {403, 404}) {
            requestedUris.clear();
            responses.clear();
            responses.add(Canned.status(status));

            assertThatThrownBy(() -> client().fetchPosts(USER_ID, new FetchWindow.Since(1L), null))
                    .isInstanceOf(XApiException.class);
            assertThat(requestedUris).hasSize(1);
        }
    }

    // ---------------------------------------------------------------- 安全性

    @Test
    @DisplayName("例外メッセージに Bearer Token を含めない")
    void neverLeaksTokenInErrors() {
        String secret = "AAAAAAAAAAAAAAAAAAAAAsecret-value-that-must-not-leak";
        responses.add(Canned.status(401));

        assertThatThrownBy(() ->
                client(secret).fetchPosts(USER_ID, new FetchWindow.Since(1L), null))
                .isInstanceOf(XApiException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .as("このメッセージは ingestion_run.error_summary に入り管理画面に出る（NFR-03）")
                        .doesNotContain(secret));
    }

    @Test
    @DisplayName("トークンが未設定なら、API を叩かずに失敗する")
    void failsFastWithoutToken() {
        assertThatThrownBy(() -> client("").fetchPosts(USER_ID, new FetchWindow.Since(1L), null))
                .isInstanceOf(XApiException.class)
                .hasMessageContaining("X_BEARER_TOKEN");

        assertThat(requestedUris)
                .as("設定漏れで無意味な呼び出しを発生させない")
                .isEmpty();
    }

    @Test
    @DisplayName("エラー本文が長くても error_summary の上限を超えない長さに切る")
    void truncatesLongErrorBody() {
        responses.add(new Canned(400, "x".repeat(5_000), null));

        assertThatThrownBy(() -> client().fetchPosts(USER_ID, new FetchWindow.Since(1L), null))
                .isInstanceOf(XApiException.class)
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(500));
    }
}
