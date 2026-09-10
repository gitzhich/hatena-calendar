package dev.mzhin.hatenacal.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlacesHttpClient} の検証。
 *
 * <p><b>実 Places API は叩かない。</b> JDK 同梱の HTTP サーバをローカルに立て、
 * 記録済みのレスポンスを返す（{@code XApiHttpClientTest} と同じ作り）。
 * 使う SKU は無料だが、テストが外部サービスの可用性に左右されないようにするため。
 */
class PlacesHttpClientTest {

    private HttpServer server;
    private final Deque<Canned> responses = new ArrayDeque<>();
    private final List<Request> requests = new ArrayList<>();

    private record Canned(int status, String body) {
        static Canned ok(String body) {
            return new Canned(200, body);
        }
    }

    private record Request(String uri, String apiKey, String fieldMask, String body) {
    }

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
        requests.add(new Request(
                exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("X-Goog-Api-Key"),
                exchange.getRequestHeaders().getFirst("X-Goog-FieldMask"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));

        Canned canned = responses.isEmpty()
                ? Canned.ok("{\"places\":[]}")
                : responses.poll();
        byte[] body = canned.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(canned.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private PlacesHttpClient client() {
        return client("test-key");
    }

    private PlacesHttpClient client(String apiKey) {
        return new PlacesHttpClient(new PlacesProperties(
                apiKey,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "ja",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)));
    }

    // ------------------------------------------------------------ リクエスト

    @Test
    @DisplayName("フィールドマスクは places.id だけ。増やすと課金対象の SKU に移る")
    void requestsIdOnlyFieldMask() {
        responses.add(Canned.ok("{\"places\":[{\"id\":\"ChIJ_test\"}]}"));

        client().findPlaceId("愛知・大須RADHALL");

        assertThat(requests).singleElement().satisfies(r -> assertThat(r.fieldMask())
                .as("Text Search は要求したフィールドで SKU が決まる。"
                        + "places.displayName などを足した瞬間に無料でなくなる"
                        + "（docs/architecture.md「Places API に費用がかからない理由」）")
                .isEqualTo("places.id"));
    }

    @Test
    @DisplayName("API キーはヘッダで送る。URL に載せない")
    void sendsApiKeyInHeaderNotInUrl() {
        client().findPlaceId("東京・渋谷DESEO");

        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.apiKey()).isEqualTo("test-key");
            assertThat(r.uri())
                    .as("クエリに載せるとアクセスログやリファラに残りうる（docs/security.md T-08）")
                    .doesNotContain("test-key")
                    .isEqualTo("/v1/places:searchText");
        });
    }

    @Test
    @DisplayName("会場名をそのまま送り、1 件だけ要求する。国を日本に固定しない")
    void sendsVenueNameAndAsksForOneResult() {
        client().findPlaceId("韓国・SETi LIVE HALL");

        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.body()).contains("\"textQuery\":\"韓国・SETi LIVE HALL\"");
            assertThat(r.body()).contains("\"pageSize\":1");
            assertThat(r.body()).contains("\"languageCode\":\"ja\"");
            assertThat(r.body())
                    .as("実データに海外の会場がある。日本へ寄せると外す")
                    .doesNotContain("regionCode");
        });
    }

    // -------------------------------------------------------------- 結果

    @Test
    @DisplayName("最上位の候補の place_id を返す")
    void returnsTopPlaceId() {
        responses.add(Canned.ok("{\"places\":[{\"id\":\"ChIJ_radhall\"}]}"));

        assertThat(client().findPlaceId("愛知・大須RADHALL")).contains("ChIJ_radhall");
    }

    @Test
    @DisplayName("候補が無ければ empty。推測で近い施設を入れない")
    void returnsEmptyWhenNotFound() {
        responses.add(Canned.ok("{\"places\":[]}"));

        assertThat(client().findPlaceId("架空県・どこかのホール")).isEmpty();
    }

    @Test
    @DisplayName("places 自体が無くても落ちない")
    void returnsEmptyWhenPlacesMissing() {
        responses.add(Canned.ok("{}"));

        assertThat(client().findPlaceId("架空県・どこかのホール")).isEmpty();
    }

    // -------------------------------------------------------------- 失敗

    @Test
    @DisplayName("エラー応答は例外にする。「見つからなかった」と混ぜない")
    void throwsOnErrorStatus() {
        responses.add(new Canned(500, "{\"error\":{\"message\":\"internal\"}}"));

        assertThatThrownBy(() -> client().findPlaceId("愛知・大須RADHALL"))
                .as("empty を返すと、障害中に「存在しない会場」として記録され、"
                        + "再試行が 7 日先へ飛ぶ")
                .isInstanceOf(PlacesException.class)
                .satisfies(e -> assertThat(((PlacesException) e).status()).isEqualTo(500));
    }

    @Test
    @DisplayName("再試行しない。失敗しても翌日また走る")
    void doesNotRetry() {
        responses.add(new Canned(503, "{\"error\":{}}"));

        assertThatThrownBy(() -> client().findPlaceId("愛知・大須RADHALL"))
                .isInstanceOf(PlacesException.class);

        assertThat(requests)
                .as("障害中に叩き続ける理由が無い。未解決の間も地図リンクは名前検索で機能する")
                .hasSize(1);
    }

    @Test
    @DisplayName("キーが未設定なら 1 回も叩かない")
    void doesNotCallWithoutApiKey() {
        assertThatThrownBy(() -> client("").findPlaceId("愛知・大須RADHALL"))
                .isInstanceOf(PlacesException.class);

        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("エラーメッセージに API キーを含めない")
    void errorMessageOmitsApiKey() {
        responses.add(new Canned(403, "{\"error\":{\"message\":\"API key not valid\"}}"));

        assertThatThrownBy(() -> client("super-secret-key").findPlaceId("愛知・大須RADHALL"))
                .isInstanceOf(PlacesException.class)
                .hasMessageNotContaining("super-secret-key");
    }
}
