package dev.mzhin.hatenacal.venue;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mzhin.hatenacal.support.MovableClock;
import dev.mzhin.hatenacal.support.StubPlacesClient;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
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
 * place_id の解決（ADR-0022「既存データの初期投入」の段 2）。
 *
 * <p>ここで固定するのは<b>いつ叩き、結果をどう記録するか</b>である。
 * HTTP そのものは {@code PlacesHttpClientTest} が実サーバで見ている。
 *
 * <p><b>「見つからない」と「到達できない」の扱いを分けることが要</b>で、
 * 取り違えると Google の障害中に再試行が 7 日先へ飛ぶ。
 */
@SpringBootTest
@Import(PlaceIdResolutionIT.Stubs.class)
@TestPropertySource(properties = "places.api-key=test-key")
class PlaceIdResolutionIT {

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        StubPlacesClient stubPlacesClient() {
            return new StubPlacesClient();
        }

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired
    private PlaceIdResolutionService service;

    @Autowired
    private VenueService venues;

    @Autowired
    private VenueRepository repository;

    @Autowired
    private StubPlacesClient places;

    @Autowired
    private Clock clock;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void clean() {
        places.reset();
        tx.executeWithoutResult(s -> {
            // 外部キーがあるので出演情報から消す
            em.createNativeQuery("DELETE FROM appearance").executeUpdate();
            em.createNativeQuery("DELETE FROM venue").executeUpdate();
        });
    }

    private Long venue(String name) {
        return tx.execute(s -> venues.findOrCreate(name).getId());
    }

    private Venue reload(Long id) {
        return tx.execute(s -> repository.findById(id).orElseThrow());
    }

    /** 最後に解決を試みた日時を直接ずらす。7 日の間隔を検証するため。 */
    private void checkedAt(Long id, OffsetDateTime at) {
        tx.executeWithoutResult(s -> em
                .createNativeQuery("UPDATE venue SET place_id_checked_at = ?1 WHERE id = ?2")
                .setParameter(1, at)
                .setParameter(2, id)
                .executeUpdate());
    }

    private void advance(Duration amount) {
        ((MovableClock) clock).advance(amount);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    // ------------------------------------------------------------ 記録の規則

    @Test
    @DisplayName("見つかれば place_id を入れ、試行日時を記録する")
    void storesResolvedPlaceId() {
        Long id = venue("愛知・大須RADHALL");
        places.answer("愛知・大須RADHALL", "ChIJ_radhall");

        assertThat(service.resolveMissing(10)).isEqualTo(1);

        Venue saved = reload(id);
        assertThat(saved.getPlaceId()).isEqualTo("ChIJ_radhall");
        assertThat(saved.getPlaceIdCheckedAt().toInstant()).isEqualTo(now().toInstant());
    }

    @Test
    @DisplayName("見つからなくても試行日時を記録する。毎日叩かないため")
    void recordsAttemptEvenWhenNotFound() {
        Long id = venue("架空県・どこかのホール");

        assertThat(service.resolveMissing(10)).isZero();

        Venue saved = reload(id);
        assertThat(saved.getPlaceId()).isNull();
        assertThat(saved.getPlaceIdCheckedAt())
                .as("記録しないと、Google に存在しない会場を毎日叩き続ける")
                .isNotNull()
                .satisfies(at -> assertThat(at.toInstant()).isEqualTo(now().toInstant()));
    }

    @Test
    @DisplayName("到達できなかったときは記録しない。試せていないため")
    void doesNotRecordWhenUnreachable() {
        Long id = venue("愛知・大須RADHALL");
        places.failAll(503);

        assertThat(service.resolveMissing(10)).isZero();

        assertThat(reload(id).getPlaceIdCheckedAt())
                .as("記録すると、障害が明けても再試行が 7 日先へ飛ぶ")
                .isNull();
    }

    @Test
    @DisplayName("到達できなくなったらその場で打ち切る。残りを叩き続けない")
    void stopsAtFirstUnreachable() {
        venue("愛知・大須RADHALL");
        venue("東京・渋谷DESEO");
        venue("大阪・味園ユニバース");
        places.failAll(503);

        service.resolveMissing(10);

        assertThat(places.queries())
                .as("障害中に上限まで叩いても何も得られない")
                .hasSize(1);
    }

    // ------------------------------------------------------------ 対象の選び方

    @Test
    @DisplayName("管理者が編集した会場は拾わない")
    void skipsManuallyEditedVenues() {
        Long id = venue("金沢・REDSUN");
        tx.executeWithoutResult(s -> em
                .createNativeQuery("UPDATE venue SET manually_edited = true WHERE id = ?1")
                .setParameter(1, id)
                .executeUpdate());

        service.resolveMissing(10);

        assertThat(places.queries())
                .as("人が確認した値のほうが強い。消した place_id を翌日埋め直さない")
                .isEmpty();
    }

    @Test
    @DisplayName("解決済みの会場は拾わない")
    void skipsResolvedVenues() {
        venue("愛知・大須RADHALL");
        places.answer("愛知・大須RADHALL", "ChIJ_radhall");
        service.resolveMissing(10);
        places.reset();

        assertThat(service.resolveMissing(10)).isZero();
        assertThat(places.queries())
                .as("place_id は無期限に保存してよい。引き直す理由が無い")
                .isEmpty();
    }

    @Test
    @DisplayName("7 日経つまで再試行しない")
    void waitsBeforeRetrying() {
        Long id = venue("架空県・どこかのホール");
        service.resolveMissing(10);
        places.reset();

        advance(PlaceIdResolutionService.RETRY_INTERVAL.minusHours(1));
        service.resolveMissing(10);

        assertThat(places.queries()).isEmpty();
        assertThat(reload(id).getPlaceIdCheckedAt()).isNotNull();
    }

    @Test
    @DisplayName("7 日経てば再試行する。表記を直せば見つかるようになるため")
    void retriesAfterInterval() {
        venue("架空県・どこかのホール");
        service.resolveMissing(10);
        places.reset();

        advance(PlaceIdResolutionService.RETRY_INTERVAL.plusHours(1));
        service.resolveMissing(10);

        assertThat(places.queries()).containsExactly("架空県・どこかのホール");
    }

    @Test
    @DisplayName("一度も試していない会場を先に拾う。初期投入が押しのけられない")
    void prefersNeverTriedVenues() {
        Long old = venue("東京・渋谷DESEO");
        checkedAt(old, now().minus(Duration.ofDays(30)));
        venue("愛知・大須RADHALL");

        service.resolveMissing(1);

        assertThat(places.queries()).containsExactly("愛知・大須RADHALL");
    }

    @Test
    @DisplayName("上限を超えて叩かない")
    void respectsBatchLimit() {
        venue("東京・渋谷DESEO");
        venue("愛知・大須RADHALL");
        venue("大阪・味園ユニバース");

        service.resolveMissing(2);

        assertThat(places.queries())
                .as("外部 API を叩くので、残りが尽きるまで繰り返さない")
                .hasSize(2);
    }

    // ------------------------------------------------------------ 1 件だけ解決

    @Test
    @DisplayName("再試行の間隔を待たずに 1 件だけ解決できる")
    void resolvesOneOnDemand() {
        Long id = venue("架空県・どこかのホール");
        service.resolveMissing(10);
        places.reset();
        places.answer("架空県・どこかのホール", "ChIJ_found");

        assertThat(service.resolveOne(reload(id))).isTrue();

        assertThat(reload(id).getPlaceId())
                .as("表記を直した直後に確かめられないと、管理者は 7 日待つことになる")
                .isEqualTo("ChIJ_found");
    }

    // ------------------------------------------------------------ キー未設定

    @Test
    @DisplayName("API キーが未設定なら 1 回も叩かない。アプリは動き続ける")
    void doesNothingWithoutApiKey() {
        StubPlacesClient unused = new StubPlacesClient();
        PlaceIdResolutionService noKey = new PlaceIdResolutionService(repository, unused,
                new PlacesProperties("", "http://127.0.0.1", "ja",
                        Duration.ofSeconds(1), Duration.ofSeconds(1)),
                tx, clock);
        venue("愛知・大須RADHALL");

        assertThat(noKey.resolveMissing(10)).isZero();
        assertThat(unused.queries())
                .as("未解決の会場は名前検索の地図リンクに落ちるだけ。画面は壊れない")
                .isEmpty();
    }
}
