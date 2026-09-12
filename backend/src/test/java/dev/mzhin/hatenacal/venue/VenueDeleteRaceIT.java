package dev.mzhin.hatenacal.venue;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mzhin.hatenacal.config.ApiKeyFilter;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.Map;
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

/**
 * 削除の競合（docs/api.md「会場の一覧と編集」）。
 *
 * <p><b>件数を数えてから消すまでの間に出演情報が紐づく。</b> 取り込みの定期実行が
 * 30 分ごとに {@code venue_id} を付けるため、この隙間は実在する
 * （ADR-0022「既存データの初期投入」）。ここでは件数を 0 と答えるスタブで再現する。
 *
 * <p><b>確かめるのは 500 に落ちないこと。</b> 外部キーは DB が守るので消えはしないが、
 * そのままだと「サーバ内部エラー」になり、防げた衝突が原因不明の障害に見える。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(VenueDeleteRaceIT.StaleCount.class)
@TestPropertySource(properties = {
    "INTERNAL_API_KEY=public-key",
    "INTERNAL_ADMIN_API_KEY=admin-key",
    "places.api-key=test-key"
})
class VenueDeleteRaceIT {

    private static final String ADMIN_KEY = "admin-key";
    private static final String PATH = "/api/admin/venues";

    /** 常に「参照 0 件」と答える。数え終わった直後に紐づいた状態と同じになる。 */
    @TestConfiguration
    static class StaleCount {

        @Bean
        @Primary
        VenueUsageCounter staleUsageCounter() {
            return (Collection<Long> ids) -> Map.of();
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private VenueService venues;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    @AfterEach
    void clean() {
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM appearance").executeUpdate();
            em.createNativeQuery("DELETE FROM venue").executeUpdate();
        });
    }

    @Test
    @DisplayName("数えた後に紐づいた会場の削除は 409。500 に混ぜない")
    void refusesDeleteWhenReferenceAppearsAfterCounting() throws Exception {
        Long id = tx.execute(s -> venues.findOrCreate("愛知・大須RADHALL").getId());
        tx.executeWithoutResult(s -> em.createNativeQuery("""
                INSERT INTO appearance (appearance_date, event_name, event_key, venue_name,
                    venue_id, source_url, source_type)
                VALUES (DATE '2026-09-20', '『a』', 'a', '愛知・大須RADHALL', ?1,
                    'https://x.com/a/status/1', 'MANUAL')
                """).setParameter(1, id).executeUpdate());

        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH + "/" + id))
                        .header(ApiKeyFilter.ADMIN_HEADER, ADMIN_KEY)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode())
                .as("外部キー違反を 500 のまま返すと、防げた衝突が原因不明の障害に見える")
                .isEqualTo(409);
        assertThat(res.body())
                .as("SQL もクラス名もレスポンスに出さない（NFR-03）")
                .doesNotContain("constraint")
                .doesNotContain("dev.mzhin");
        Long remaining = tx.execute(s -> ((Number) em
                .createNativeQuery("SELECT count(*) FROM venue WHERE id = ?1")
                .setParameter(1, id).getSingleResult()).longValue());
        assertThat(remaining).as("消えていては困る").isEqualTo(1);
    }
}
