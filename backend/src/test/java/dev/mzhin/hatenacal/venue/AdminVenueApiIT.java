package dev.mzhin.hatenacal.venue;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mzhin.hatenacal.config.ApiKeyFilter;
import dev.mzhin.hatenacal.support.StubPlacesClient;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

/** 会場の管理 API の契約（docs/api.md「会場の一覧と編集」）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AdminVenueApiIT.Stubs.class)
@TestPropertySource(properties = {
    "INTERNAL_API_KEY=public-key",
    "INTERNAL_ADMIN_API_KEY=admin-key",
    "places.api-key=test-key"
})
class AdminVenueApiIT {

    private static final String PUBLIC_KEY = "public-key";
    private static final String ADMIN_KEY = "admin-key";
    private static final String PATH = "/api/admin/venues";

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        StubPlacesClient stubPlacesClient() {
            return new StubPlacesClient();
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private VenueService venues;

    @Autowired
    private StubPlacesClient places;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    @AfterEach
    void clean() {
        places.reset();
        tx.executeWithoutResult(s -> {
            // 外部キーがあるので出演情報から消す
            em.createNativeQuery("DELETE FROM appearance").executeUpdate();
            em.createNativeQuery("DELETE FROM venue").executeUpdate();
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
        b.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> res = send("GET", path, ADMIN_KEY, null);
        assertThat(res.statusCode()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private Long venue(String name) {
        return tx.execute(s -> venues.findOrCreate(name).getId());
    }

    /** 会場を指す出演情報を 1 件作る。appearanceCount の検証に使う。 */
    private void appearance(String eventKey, Long venueId, String venueName) {
        tx.executeWithoutResult(s -> em.createNativeQuery("""
                INSERT INTO appearance (appearance_date, event_name, event_key, venue_name,
                    venue_id, source_url, source_type)
                VALUES (DATE '2026-09-20', ?1, ?2, ?3, ?4, 'https://x.com/a/status/1', 'MANUAL')
                """)
                .setParameter(1, "『" + eventKey + "』")
                .setParameter(2, eventKey)
                .setParameter(3, venueName)
                .setParameter(4, venueId)
                .executeUpdate());
    }

    /**
     * 地域を指定して会場を 1 行作る。
     *
     * <p>並び順の検証に使う。{@link VenueService#findOrCreate} は表記から地域を引くため、
     * 全 10 区分を揃えられない。
     */
    private void venue(String displayName, Region region) {
        tx.executeWithoutResult(s -> em.createNativeQuery("""
                INSERT INTO venue (venue_key, display_name, region) VALUES (?1, ?2, ?3)
                """)
                .setParameter(1, displayName)
                .setParameter(2, displayName)
                .setParameter(3, region.name())
                .executeUpdate());
    }

    private static List<String> fields(JsonNode items, String name) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            values.add(items.get(i).path(name).asString());
        }
        return values;
    }

    private String editBody(String displayName, String region, String placeId) {
        return """
                {"displayName": "%s", "region": "%s", "placeId": %s}
                """.formatted(displayName, region,
                placeId == null ? "null" : "\"" + placeId + "\"");
    }

    // -------------------------------------------------------------- 一覧

    @Test
    @DisplayName("会場を返し、その会場を指す出演情報の件数を添える")
    void listsVenuesWithAppearanceCount() throws Exception {
        Long id = venue("愛知・大須RADHALL");
        appearance("a", id, "愛知・大須RADHALL");
        appearance("b", id, "愛知・大須RAD HALL");

        JsonNode body = get(PATH);

        assertThat(body.path("totalElements").asInt()).isEqualTo(1);
        JsonNode item = body.path("items").get(0);
        assertThat(item.path("venueKey").asString()).isEqualTo("愛知大須radhall");
        assertThat(item.path("displayName").asString()).isEqualTo("愛知・大須RADHALL");
        assertThat(item.path("region").asString()).isEqualTo("CHUBU");
        assertThat(item.path("placeId").isNull()).isTrue();
        assertThat(item.path("manuallyEdited").asBoolean()).isFalse();
        assertThat(item.path("appearanceCount").asLong())
                .as("直す価値の大きさが分かる。1 行直せば全件に効く")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("出演情報が 1 件も無い会場は 0 件として返る")
    void countsZeroForUnusedVenue() throws Exception {
        venue("愛知・大須RADHALL");

        assertThat(get(PATH).path("items").get(0).path("appearanceCount").asLong()).isZero();
    }

    @Test
    @DisplayName("unresolved=true で place_id が未解決の会場だけ返る")
    void filtersUnresolved() throws Exception {
        venue("愛知・大須RADHALL");
        Long resolved = venue("東京・渋谷DESEO");
        send("PUT", PATH + "/" + resolved, ADMIN_KEY,
                editBody("東京・渋谷DESEO", "KANTO", "ChIJ_deseo"));

        JsonNode body = get(PATH + "?unresolved=true");

        assertThat(body.path("totalElements").asInt()).isEqualTo(1);
        assertThat(body.path("items").get(0).path("displayName").asString())
                .isEqualTo("愛知・大須RADHALL");
    }

    @Test
    @DisplayName("size は範囲外を丸める。400 にしない")
    void roundsPageSize() throws Exception {
        venue("愛知・大須RADHALL");

        assertThat(get(PATH + "?size=0").path("size").asInt()).isEqualTo(1);
        assertThat(get(PATH + "?size=500").path("size").asInt()).isEqualTo(100);
        assertThat(get(PATH + "?page=-1").path("page").asInt()).isZero();
    }

    @Test
    @DisplayName("公開キーでは通らない")
    void rejectsPublicKey() throws Exception {
        assertThat(send("GET", PATH, PUBLIC_KEY, null).statusCode()).isEqualTo(403);
    }

    // -------------------------------------------------------------- 並び順

    @Test
    @DisplayName("地方でまとまり、Region の宣言順に並ぶ")
    void ordersByRegionDeclaration() throws Exception {
        Region[] regions = Region.values();
        for (int i = 0; i < regions.length; i++) {
            // 表記は宣言順と逆に振る。displayName だけで並べた結果と区別がつく
            venue("会場" + (char) ('A' + regions.length - 1 - i), regions[i]);
        }

        JsonNode items = get(PATH + "?size=100").path("items");

        assertThat(fields(items, "region"))
                .as("Region に地方を足して ORDER BY の CASE を直し忘れると、ここだけが崩れる")
                .containsExactlyElementsOf(
                        Arrays.stream(regions).map(Enum::name).toList());
    }

    @Test
    @DisplayName("同じ地方の中は表記の昇順")
    void ordersByDisplayNameWithinRegion() throws Exception {
        venue("東京・Z会場", Region.KANTO);
        venue("東京・A会場", Region.KANTO);
        venue("北海道・M会場", Region.HOKKAIDO);

        JsonNode items = get(PATH).path("items");

        assertThat(fields(items, "displayName"))
                .containsExactly("北海道・M会場", "東京・A会場", "東京・Z会場");
    }

    @Test
    @DisplayName("unresolved=true でも地方の順は同じ")
    void ordersUnresolvedByRegion() throws Exception {
        venue("九州・K会場", Region.KYUSHU);
        venue("関東・T会場", Region.KANTO);

        JsonNode items = get(PATH + "?unresolved=true").path("items");

        assertThat(fields(items, "region"))
                .as("絞り込みで別のクエリに切り替わる。並び順はそちらにも要る")
                .containsExactly("KANTO", "KYUSHU");
    }

    // -------------------------------------------------------------- 1 件取得

    @Test
    @DisplayName("1 件取得は一覧の 1 要素と同じ形を返す")
    void getsOneVenue() throws Exception {
        Long id = venue("愛知・大須RADHALL");
        appearance("a", id, "愛知・大須RADHALL");

        JsonNode body = get(PATH + "/" + id);

        assertThat(body.path("id").asLong()).isEqualTo(id);
        assertThat(body.path("venueKey").asString()).isEqualTo("愛知大須radhall");
        assertThat(body.path("displayName").asString()).isEqualTo("愛知・大須RADHALL");
        assertThat(body.path("region").asString()).isEqualTo("CHUBU");
        assertThat(body.path("placeId").isNull()).isTrue();
        assertThat(body.path("manuallyEdited").asBoolean()).isFalse();
        assertThat(body.path("areaOnly").asBoolean()).isFalse();
        assertThat(body.path("appearanceCount").asLong())
                .as("編集画面が一覧と同じ形を扱えるようにする")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("存在しない会場の取得は 404")
    void getMissingVenue() throws Exception {
        assertThat(send("GET", PATH + "/999999", ADMIN_KEY, null).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("1 件取得も公開キーでは通らない")
    void rejectsPublicKeyOnGetOne() throws Exception {
        Long id = venue("愛知・大須RADHALL");

        assertThat(send("GET", PATH + "/" + id, PUBLIC_KEY, null).statusCode()).isEqualTo(403);
    }

    // -------------------------------------------------------------- 編集

    @Test
    @DisplayName("編集すると manuallyEdited が立ち、以後の自動処理が触らなくなる")
    void updateMarksManuallyEdited() throws Exception {
        Long id = venue("金沢・REDSUN");

        HttpResponse<String> res = send("PUT", PATH + "/" + id, ADMIN_KEY,
                editBody("金沢・REDSUN", "CHUBU", "ChIJ_redsun"));

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(res.body());
        assertThat(body.path("region").asString()).isEqualTo("CHUBU");
        assertThat(body.path("placeId").asString()).isEqualTo("ChIJ_redsun");
        assertThat(body.path("manuallyEdited").asBoolean())
                .as("人が確認した値のほうが強い（ADR-0022）")
                .isTrue();
        assertThat(body.path("venueKey").asString())
                .as("表記から機械的に決まる値。変えると出演情報が別の会場に化ける")
                .isEqualTo("金沢redsun");
    }

    @Test
    @DisplayName("placeId に null を送ると解決前に戻る")
    void updateCanClearPlaceId() throws Exception {
        Long id = venue("金沢・REDSUN");
        send("PUT", PATH + "/" + id, ADMIN_KEY, editBody("金沢・REDSUN", "CHUBU", "ChIJ_wrong"));

        HttpResponse<String> res = send("PUT", PATH + "/" + id, ADMIN_KEY,
                editBody("金沢・REDSUN", "CHUBU", null));

        assertThat(json.readTree(res.body()).path("placeId").isNull())
                .as("誤って解決した場合の取り消し。名前検索の地図リンクに落ちる")
                .isTrue();
    }

    @Test
    @DisplayName("displayName が空なら 400")
    void rejectsBlankDisplayName() throws Exception {
        Long id = venue("金沢・REDSUN");

        assertThat(send("PUT", PATH + "/" + id, ADMIN_KEY,
                editBody("", "CHUBU", null)).statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("存在しない会場は 404")
    void updateMissingVenue() throws Exception {
        assertThat(send("PUT", PATH + "/999999", ADMIN_KEY,
                editBody("架空", "KANTO", null)).statusCode()).isEqualTo(404);
    }

    // -------------------------------------------------------------- 即時解決

    @Test
    @DisplayName("再試行の間隔を待たずに解決できる")
    void resolvesOnDemand() throws Exception {
        Long id = venue("愛知・大須RADHALL");
        places.answer("愛知・大須RADHALL", "ChIJ_radhall");

        HttpResponse<String> res = send("POST", PATH + "/" + id + "/resolve-place-id",
                ADMIN_KEY, null);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(json.readTree(res.body()).path("placeId").asString())
                .isEqualTo("ChIJ_radhall");
    }

    @Test
    @DisplayName("見つからなくても 200。試行日時だけが進む")
    void resolveRecordsAttemptWhenNotFound() throws Exception {
        Long id = venue("架空県・どこかのホール");

        HttpResponse<String> res = send("POST", PATH + "/" + id + "/resolve-place-id",
                ADMIN_KEY, null);

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(res.body());
        assertThat(body.path("placeId").isNull())
                .as("推測で近い施設を入れない（docs/security.md T-08）")
                .isTrue();
        assertThat(body.path("placeIdCheckedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("管理者が編集した会場への解決は 409")
    void refusesResolveOnManuallyEdited() throws Exception {
        Long id = venue("金沢・REDSUN");
        send("PUT", PATH + "/" + id, ADMIN_KEY, editBody("金沢・REDSUN", "CHUBU", "ChIJ_redsun"));

        HttpResponse<String> res = send("POST", PATH + "/" + id + "/resolve-place-id",
                ADMIN_KEY, null);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(places.queries())
                .as("人が入れた値を機械が消さない。叩く前に止める")
                .isEmpty();
    }

    @Test
    @DisplayName("地域だけの行への解決は 409")
    void refusesResolveOnAreaOnly() throws Exception {
        Long id = tx.execute(s -> venues.findOrCreateArea("東京").getId());

        HttpResponse<String> res = send("POST", PATH + "/" + id + "/resolve-place-id",
                ADMIN_KEY, null);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(places.queries())
                .as("会場ではないので同定できない。叩く前に止める")
                .isEmpty();
    }

    @Test
    @DisplayName("一覧は地域だけの行を areaOnly で区別できる")
    void listMarksAreaOnly() throws Exception {
        tx.executeWithoutResult(s -> venues.findOrCreateArea("東京"));
        venue("愛知・大須RADHALL");

        JsonNode body = get(PATH);

        assertThat(body.path("items")).hasSize(2);
        assertThat(body.path("items")).anySatisfy(item -> {
            assertThat(item.path("displayName").asString()).isEqualTo("東京");
            assertThat(item.path("areaOnly").asBoolean()).isTrue();
            assertThat(item.path("region").asString()).isEqualTo("KANTO");
        });
        assertThat(body.path("items")).anySatisfy(item -> {
            assertThat(item.path("displayName").asString()).isEqualTo("愛知・大須RADHALL");
            assertThat(item.path("areaOnly").asBoolean()).isFalse();
        });
    }

    @Test
    @DisplayName("Google に到達できないときは 502。500 に混ぜない")
    void returnsBadGatewayWhenPlacesFails() throws Exception {
        Long id = venue("愛知・大須RADHALL");
        places.failAll(503);

        HttpResponse<String> res = send("POST", PATH + "/" + id + "/resolve-place-id",
                ADMIN_KEY, null);

        assertThat(res.statusCode()).isEqualTo(502);
        assertThat(res.body())
                .as("相手の応答を返さない（NFR-03）")
                .doesNotContain("503");
    }
}
