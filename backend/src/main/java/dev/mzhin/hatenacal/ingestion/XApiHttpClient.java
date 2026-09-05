package dev.mzhin.hatenacal.ingestion;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /2/users/{id}/tweets} で投稿を取得する（docs/x-integration.md「投稿の取得」）。
 *
 * <p>リトライは 429 / 5xx / 通信失敗のみ。401・403・404 は再試行しても直らないため
 * 即座に失敗させる（同 docs/x-integration.md「エラーハンドリング」）。リトライ回数には上限がある（FR-43）。
 */
@Component
public class XApiHttpClient implements XApiClient {

    private static final Logger log = LoggerFactory.getLogger(XApiHttpClient.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 画像を保持しないため expansions と media.fields は指定しない（LR-03 / docs/x-integration.md「投稿の取得」）。 */
    private static final String TWEET_FIELDS = "created_at,note_tweet";
    private static final String EXCLUDE = "replies,retweets";

    /** error_summary は 500 字以内（docs/data-model.md）。本文はさらに短く切る。 */
    private static final int MAX_BODY_IN_MESSAGE = 200;

    private final XApiProperties properties;
    private final Sleeper sleeper;
    private final RestClient restClient;

    public XApiHttpClient(XApiProperties properties, Sleeper sleeper) {
        this.properties = properties;
        this.sleeper = sleeper;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Override
    public FetchResult fetchPosts(long xUserId, FetchWindow window, String paginationToken) {
        if (!properties.configured()) {
            throw new XApiException("X_BEARER_TOKEN が設定されていません", 0);
        }
        URI uri = buildUri(xUserId, window, paginationToken);
        return parse(getWithRetry(uri));
    }

    /** 取得範囲は必ず入る。範囲なしの URI をここで組み立てられない（FR-40）。 */
    private URI buildUri(long xUserId, FetchWindow window, String paginationToken) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(properties.baseUrl())
                .path("/2/users/{id}/tweets")
                .queryParam("max_results", properties.maxResults())
                .queryParam("exclude", EXCLUDE)
                .queryParam("tweet.fields", TWEET_FIELDS);

        switch (window) {
            case FetchWindow.Since since -> b.queryParam("since_id", since.sinceId());
            case FetchWindow.From from ->
                    b.queryParam("start_time", rfc3339(from.startTime()));
        }
        if (paginationToken != null && !paginationToken.isBlank()) {
            b.queryParam("pagination_token", paginationToken);
        }
        return b.build(xUserId);
    }

    /**
     * X API が受け付ける RFC3339 の形（{@code 2026-06-03T06:47:04Z}）にする。
     *
     * <p><b>秒未満を落とす。</b> {@link OffsetDateTime#toString()} をそのまま渡すと
     * {@code 2026-06-03T06:47:04.021243663Z} のようにナノ秒が付き、X API が
     * <b>HTTP 400 で拒否する</b>（{@code is not a valid RFC3339 date-time}）。
     * 実際に本番の初回バックフィルがこれで失敗した。
     *
     * <p>UTC に寄せてから整形する。オフセット付きの表記（{@code +09:00}）も
     * RFC3339 としては正しいが、送る形を 1 つに固定しておくほうが読み違えがない。
     */
    static String rfc3339(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String getWithRetry(URI uri) {
        for (int attempt = 0; ; attempt++) {
            Response response = execute(uri);

            if (response.status() == 200) {
                return response.body();
            }
            boolean last = attempt >= properties.maxRetries();
            if (!retryable(response.status()) || last) {
                throw new XApiException(describe(response, attempt), response.status());
            }
            Duration wait = backoff(attempt, response);
            log.warn("X API が {} を返したため {} 待って再試行する（{}/{}）",
                    response.status(), wait, attempt + 1, properties.maxRetries());
            sleeper.sleep(wait);
        }
    }

    private Response execute(URI uri) {
        try {
            return restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.bearerToken())
                    .exchange((request, response) -> new Response(
                            response.getStatusCode().value(),
                            response.bodyTo(String.class),
                            response.getHeaders().getFirst("x-rate-limit-reset")), false);
        } catch (ResourceAccessException e) {
            // 接続失敗・タイムアウト。リトライ対象（docs/x-integration.md「エラーハンドリング」）
            return new Response(0, e.getClass().getSimpleName(), null);
        }
    }

    /** 429 と 5xx と通信失敗のみ再試行する。401 / 403 / 404 は再試行しても直らない。 */
    private static boolean retryable(int status) {
        return status == 0 || status == 429 || status >= 500;
    }

    /**
     * 待機時間。429 で {@code x-rate-limit-reset} が返っていればそれに従い、
     * 無ければ指数バックオフ。いずれも上限で頭打ちにする。
     */
    private Duration backoff(int attempt, Response response) {
        Duration exponential = properties.initialBackoff().multipliedBy(1L << attempt);
        Duration wait = exponential;

        if (response.status() == 429 && response.rateLimitReset() != null) {
            try {
                long resetEpoch = Long.parseLong(response.rateLimitReset().trim());
                long seconds = resetEpoch - System.currentTimeMillis() / 1000L;
                if (seconds > 0) {
                    wait = Duration.ofSeconds(seconds);
                }
            } catch (NumberFormatException ignored) {
                // ヘッダが読めなければ指数バックオフのまま
            }
        }
        return wait.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : wait;
    }

    /** <b>トークンを含めない。</b>このメッセージは error_summary に入り管理画面に出る。 */
    private static String describe(Response response, int attempt) {
        String body = response.body() == null ? "" : response.body();
        if (body.length() > MAX_BODY_IN_MESSAGE) {
            body = body.substring(0, MAX_BODY_IN_MESSAGE) + "…";
        }
        String status = response.status() == 0 ? "通信失敗" : "HTTP " + response.status();
        return "X API の取得に失敗しました（" + status + ", 試行 " + (attempt + 1) + " 回）: " + body;
    }

    private static FetchResult parse(String body) {
        JsonNode root = MAPPER.readTree(body);
        JsonNode data = root.path("data");

        List<SourcePost> posts = new ArrayList<>();
        for (JsonNode node : data) {
            posts.add(new SourcePost(
                    node.path("id").asLong(),
                    node.path("text").asString(""),
                    node.path("note_tweet").path("text").asString(null),
                    OffsetDateTime.parse(node.path("created_at").asString())));
        }
        String nextToken = root.path("meta").path("next_token").asString(null);
        return new FetchResult(posts, nextToken);
    }

    /** 1 回の呼び出しの生の結果。 */
    private record Response(int status, String body, String rateLimitReset) {
    }
}
