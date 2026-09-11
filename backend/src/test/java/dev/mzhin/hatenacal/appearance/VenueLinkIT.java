package dev.mzhin.hatenacal.appearance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mzhin.hatenacal.venue.Region;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出演情報と会場の紐づけ（ADR-0022）。
 *
 * <p>初期投入（既存行への充填）と通常運用（登録時の紐づけ）が
 * <b>同じ経路</b>であることを固定する。
 */
@SpringBootTest
@Transactional
class VenueLinkIT {

    @Autowired
    private AppearanceService service;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void clean() {
        // 外部キーがあるので出演情報から消す
        em.createNativeQuery("DELETE FROM appearance").executeUpdate();
        em.createNativeQuery("DELETE FROM venue").executeUpdate();
    }

    private AppearanceCommand command(String eventName, String venueName, LocalTime start) {
        return command(eventName, venueName, null, start);
    }

    private AppearanceCommand command(String eventName, String venueName, String areaName,
            LocalTime start) {
        return new AppearanceCommand(LocalDate.of(2026, 9, 20), eventName, venueName, areaName,
                start, null, null, null, null, "https://x.com/a/status/1", null);
    }

    private long venueCount() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM venue").getSingleResult())
                .longValue();
    }

    private void flush() {
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("登録すると会場が作られ、地域が判定される")
    void createLinksVenue() {
        AdminAppearanceDto saved = service.create(command("『FES』", "愛知・大須RADHALL", null));

        assertThat(saved.venueId()).isNotNull();
        assertThat(saved.venueRegion()).isEqualTo(Region.CHUBU);
        assertThat(saved.venuePlaceId())
                .as("place_id の解決は別の経路。登録時には未解決のまま")
                .isNull();
    }

    @Test
    @DisplayName("表記がゆれても同じ会場に寄る")
    void spellingVariantsShareOneVenue() {
        AdminAppearanceDto a = service.create(command("『A』", "愛知・大須RADHALL", LocalTime.of(18, 0)));
        AdminAppearanceDto b = service.create(command("『B』", "愛知・大須RAD HALL", LocalTime.of(19, 0)));

        assertThat(a.venueId())
                .as("1 行直せば両方に効く、が成り立たなくなる")
                .isEqualTo(b.venueId());
        assertThat(venueCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("会場が空欄なら紐づけない")
    void blankVenueIsNotLinked() {
        AdminAppearanceDto saved = service.create(command("『FES』", null, null));

        assertThat(saved.venueId()).isNull();
        assertThat(saved.venueRegion()).isEqualTo(Region.UNKNOWN);
        assertThat(venueCount()).isZero();
    }

    @Test
    @DisplayName("判定できない会場でも行は作る。地域だけ UNKNOWN にする")
    void unknownRegionStillCreatesVenue() {
        AdminAppearanceDto saved = service.create(command("『FES』", "架空県・どこかのホール", null));

        assertThat(saved.venueId()).isNotNull();
        assertThat(saved.venueRegion())
                .as("推測で近い地方へ寄せない。管理者が直す")
                .isEqualTo(Region.UNKNOWN);
    }

    @Test
    @DisplayName("編集で会場を変えると紐づけも変わる")
    void updateRelinksVenue() {
        AdminAppearanceDto saved = service.create(command("『FES』", "愛知・大須RADHALL", null));
        AdminAppearanceDto moved = service.update(saved.id(),
                command("『FES』", "東京・渋谷DESEO", null));

        assertThat(moved.venueId()).isNotEqualTo(saved.venueId());
        assertThat(moved.venueRegion())
                .as("会場だけ変えて色が中部のまま残ると、表示と地図が食い違う")
                .isEqualTo(Region.KANTO);
    }

    @Test
    @DisplayName("既存の行は定期実行が埋める（初期投入）")
    void backfillLinksExistingRows() {
        // この機能より前に登録された行を再現する。venue_id は付いていない
        em.createNativeQuery("""
                INSERT INTO appearance (appearance_date, event_name, event_key, venue_name,
                    source_url, source_type)
                VALUES (DATE '2026-09-20', '『OLD』', 'old', '愛知・大須RADHALL',
                    'https://x.com/a/status/1', 'MANUAL')
                """).executeUpdate();
        flush();

        assertThat(service.linkMissingVenues(200)).isEqualTo(1);
        flush();

        assertThat(venueCount()).isEqualTo(1);
        assertThat(service.linkMissingVenues(200))
                .as("冪等。二度目は拾うものが無い")
                .isZero();
    }

    @Test
    @DisplayName("空白だけの会場名を拾わない。拾うと永久に同じ行を返し続ける")
    void backfillSkipsWhitespaceVenue() {
        em.createNativeQuery("""
                INSERT INTO appearance (appearance_date, event_name, event_key, venue_name,
                    source_url, source_type)
                VALUES (DATE '2026-09-22', '『BLANK』', 'blank', '   ',
                    'https://x.com/a/status/3', 'MANUAL')
                """).executeUpdate();
        flush();

        assertThat(service.countMissingVenues())
                .as("残件数と拾う条件がずれると、0 件しか拾えないのに残り 1 件と出続ける")
                .isZero();
        assertThat(service.linkMissingVenues(200)).isZero();
        assertThat(venueCount()).isZero();
    }

    @Test
    @DisplayName("上限を超える件数でも、繰り返せば残りが無くなる")
    void backfillDrainsBeyondOneBatch() {
        for (int i = 0; i < 5; i++) {
            em.createNativeQuery("""
                    INSERT INTO appearance (appearance_date, event_name, event_key, venue_name,
                        source_url, source_type)
                    VALUES (DATE '2026-09-20', ?, ?, '愛知・大須RADHALL',
                        'https://x.com/a/status/1', 'MANUAL')
                    """)
                    .setParameter(1, "『OLD" + i + "』")
                    .setParameter(2, "old" + i)
                    .executeUpdate();
        }
        flush();

        assertThat(service.countMissingVenues()).isEqualTo(5);
        // スケジューラと同じく、上限に届かなくなるまで繰り返す。
        // **回数に歯止めを置く。** 拾う条件と紐づける条件がずれると同じ行が
        // 永久に返るため、歯止めが無いとテストが落ちずに固まる（実際に踏んだ）。
        // 5 件を 2 件ずつなら 3 回で尽きる
        int rounds = 0;
        int total = 0;
        int linked;
        do {
            assertThat(++rounds)
                    .as("紐づけが進んでいない。拾う条件と紐づける条件がずれている")
                    .isLessThanOrEqualTo(5);
            linked = service.linkMissingVenues(2);
            total += linked;
            flush();
        } while (linked == 2);

        assertThat(total).isEqualTo(5);
        assertThat(service.countMissingVenues()).isZero();
        assertThat(venueCount())
                .as("同じ会場なので 1 行に寄る")
                .isEqualTo(1);
    }

    // --------------------------------------------------- 会場未定でも地域は持つ

    @Test
    @DisplayName("会場が未定でも、地名があれば地域が付く")
    void areaOnlyVenueCarriesRegion() {
        AdminAppearanceDto saved = service.create(
                command("『FES』", null, "東京", null));

        assertThat(saved.venueName()).as("会場は空欄のまま").isNull();
        assertThat(saved.venueId()).isNotNull();
        assertThat(saved.venueRegion())
                .as("東京の公演なのに色が付かない、が起きないようにする")
                .isEqualTo(Region.KANTO);
        assertThat(saved.venuePlaceId())
                .as("地域の行に地図リンクを作らせない（docs/security.md T-08）")
                .isNull();
    }

    @Test
    @DisplayName("地域の行は area_only が立つ。place_id の解決対象から外れる")
    void areaVenueIsMarked() {
        AdminAppearanceDto saved = service.create(command("『FES』", null, "東京", null));
        flush();

        Boolean areaOnly = (Boolean) em
                .createNativeQuery("SELECT area_only FROM venue WHERE id = ?1")
                .setParameter(1, saved.venueId())
                .getSingleResult();
        assertThat(areaOnly).isTrue();
    }

    @Test
    @DisplayName("会場名があれば、そちらが勝つ")
    void venueNameWinsOverAreaName() {
        AdminAppearanceDto saved = service.create(
                command("『FES』", "愛知・大須RADHALL", "東京", null));

        assertThat(saved.venueRegion())
                .as("会場が確定しているなら、その会場の地域が正しい")
                .isEqualTo(Region.CHUBU);
    }

    @Test
    @DisplayName("地名と判定できない文字列では地域の行を作らない")
    void unknownAreaCreatesNothing() {
        AdminAppearanceDto saved = service.create(
                command("『FES』", null, "恵比寿LIQUIDROOM", null));

        assertThat(saved.venueId())
                .as("会場名を地域の行にすると、地図リンクを出せる会場を永久に出せなくする")
                .isNull();
        assertThat(saved.venueRegion()).isEqualTo(Region.UNKNOWN);
        assertThat(venueCount()).isZero();
    }

    @Test
    @DisplayName("後から会場が埋まると、地域の行から実会場へ張り替わる")
    void filledVenueReplacesAreaLink() {
        AdminAppearanceDto area = service.create(command("『FES』", null, "東京", null));
        Long areaVenueId = area.venueId();

        AdminAppearanceDto moved = service.update(area.id(),
                command("『FES』", "愛知・大須RADHALL", "東京", null));

        assertThat(moved.venueId()).isNotEqualTo(areaVenueId);
        assertThat(moved.venueRegion()).isEqualTo(Region.CHUBU);
    }

    @Test
    @DisplayName("同じ地名は 1 行に寄る。1 行直せばその地域の全件に効く")
    void areaVenueIsShared() {
        AdminAppearanceDto a = service.create(
                command("『A』", null, "東京", LocalTime.of(18, 0)));
        AdminAppearanceDto b = service.create(
                command("『B』", null, "東京", LocalTime.of(19, 0)));

        assertThat(a.venueId()).isEqualTo(b.venueId());
        assertThat(venueCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("会場が空欄の既存行は初期投入の対象にしない")
    void backfillSkipsBlankVenue() {
        em.createNativeQuery("""
                INSERT INTO appearance (appearance_date, event_name, event_key,
                    source_url, source_type)
                VALUES (DATE '2026-09-21', '『NOVENUE』', 'novenue',
                    'https://x.com/a/status/2', 'MANUAL')
                """).executeUpdate();
        flush();

        assertThat(service.linkMissingVenues(200)).isZero();
        assertThat(venueCount()).isZero();
    }
}
