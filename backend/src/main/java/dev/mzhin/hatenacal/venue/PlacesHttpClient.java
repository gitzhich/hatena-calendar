package dev.mzhin.hatenacal.venue;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/places:searchText} で {@code place_id} を引く
 * （docs/architecture.md「Places API に費用がかからない理由」）。
 *
 * <p><b>再試行しない。</b> 失敗しても明日また走るので、その場で粘る理由が無い
 * （ADR-0022「頻度を 1 日 1 回にした理由」）。取り込み（{@code XApiHttpClient}）が
 * 再試行するのは、逃した投稿が次の実行までカレンダーに載らないためで、
 * こちらは未解決の間も名前検索の地図リンクが機能する。
 */
@Component
public class PlacesHttpClient implements PlacesClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * <b>この 1 行が「無料」を選んでいる。</b>
     *
     * <p>Text Search は<b>フィールドマスクで SKU が決まる</b>。{@code places.id} だけを
     * 求めると Essentials (ID Only) になり、Google が「Place ID を得るためのゼロコストな
     * 方法」と呼ぶ SKU に落ちる。<b>{@code places.displayName} などを 1 つ足した瞬間に
     * 課金対象の SKU へ移る</b>（docs/architecture.md「Places API に費用がかからない理由」）。
     *
     * <p>名前・住所を保存しないことは、キャッシュ制限（place_id だけが例外）と
     * 帰属表示の要否にも直結する（docs/security.md T-08）。
     */
    private static final String FIELD_MASK = "places.id";

    private static final String PATH = "/v1/places:searchText";

    /** エラーメッセージに載せる応答本文の上限。管理画面に出るため長く持たない。 */
    private static final int MAX_BODY_IN_MESSAGE = 200;

    private final PlacesProperties properties;
    private final RestClient restClient;

    public PlacesHttpClient(PlacesProperties properties) {
        this.properties = properties;

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
    public Optional<String> findPlaceId(String query) {
        if (!properties.configured()) {
            throw new PlacesException("GOOGLE_MAPS_API_KEY が設定されていません", 0);
        }
        JsonNode places = MAPPER.readTree(search(query)).path("places");
        if (!places.isArray() || places.isEmpty()) {
            // 候補なし＝この表記では同定できない。近そうな施設を当てにいかない（T-08）
            return Optional.empty();
        }
        String id = places.get(0).path("id").asString("");
        return id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    /**
     * <b>API キーはヘッダで送る。</b> クエリパラメータに載せるとアクセスログや
     * リファラに残りうる（docs/security.md T-08）。
     */
    private String search(String query) {
        try {
            return restClient.post()
                    .uri(properties.baseUrl() + PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .header("X-Goog-FieldMask", FIELD_MASK)
                    .body(requestBody(query))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        String body = response.bodyTo(String.class);
                        if (status != 200) {
                            throw new PlacesException(describe(status, body), status);
                        }
                        return body;
                    }, false);
        } catch (ResourceAccessException e) {
            // 接続失敗・タイムアウト。到達できていないので「見つからなかった」ではない
            throw new PlacesException(
                    "Places API に接続できません（" + e.getClass().getSimpleName() + "）", 0);
        }
    }

    /**
     * {@code pageSize} は 1。
     *
     * <p>候補を並べて人に選ばせる画面は無い。最上位が外れていたら管理者が直す
     * （docs/api.md「会場の一覧と編集」）。
     *
     * <p><b>{@code regionCode} を送らない。</b> 実データに {@code 韓国・SETi LIVE HALL} が
     * あり、日本に寄せると海外の会場を外す。地名は問い合わせ文字列自体が持っている
     * （{@code 愛知・大須RADHALL}）。
     */
    private Map<String, Object> requestBody(String query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("textQuery", query);
        body.put("languageCode", properties.languageCode());
        body.put("pageSize", 1);
        return body;
    }

    /** <b>API キーを含めない。</b> このメッセージはログに出る（docs/security.md T-08）。 */
    private static String describe(int status, String body) {
        String payload = body == null ? "" : body;
        if (payload.length() > MAX_BODY_IN_MESSAGE) {
            payload = payload.substring(0, MAX_BODY_IN_MESSAGE) + "…";
        }
        return "Places API が HTTP " + status + " を返しました: " + payload;
    }
}
