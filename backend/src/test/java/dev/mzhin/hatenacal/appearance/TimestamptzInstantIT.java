package dev.mzhin.hatenacal.appearance;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * TIMESTAMPTZ が JVM のタイムゾーンに依存しないことの検証。
 *
 * <p>hibernate.jdbc.time_zone を外した（DATE / TIME 列がずれるため）。
 * その副作用でシステムの時刻がずれないかを確かめる。
 *
 * <p>TIMESTAMPTZ は<b>絶対時刻</b>であり、OffsetDateTime で読み書きする限り
 * JVM の既定タイムゾーンに依存しない。DATE / TIME（暦日・ローカル時刻）とは
 * 性質が違う（docs/data-model.md 第 6 章）。
 */
@SpringBootTest
@Transactional
class TimestamptzInstantIT {

    @Autowired
    private AppearanceRepository repository;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void clean() {
        em.createNativeQuery("DELETE FROM appearance").executeUpdate();
    }

    /** created_at をリテラルで固定して書く。バインドを通さないので相殺が起きない。 */
    private void insertWithCreatedAt(String createdAtLiteral) {
        em.createNativeQuery("""
                INSERT INTO appearance (appearance_date, event_name, event_key,
                    source_url, source_type, created_at, updated_at)
                VALUES (DATE '2026-09-04', 'テスト', 'test',
                        'https://x.com/a/status/1', 'MANUAL',
                        TIMESTAMPTZ '%s', TIMESTAMPTZ '%s')
                """.formatted(createdAtLiteral, createdAtLiteral)).executeUpdate();
        em.flush();
        em.clear();
    }

    private Appearance load() {
        return repository.findForCalendar(
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4)).get(0);
    }

    @Test
    @DisplayName("UTC で書いた瞬間が、同じ瞬間として読める")
    void utcInstantRoundTrips() {
        insertWithCreatedAt("2026-09-04 12:00:00+00");

        assertThat(load().getCreatedAt().toInstant())
                .isEqualTo(Instant.parse("2026-09-04T12:00:00Z"));
    }

    @Test
    @DisplayName("JST で書いても同じ瞬間になる。表記が違うだけで指す時点は同一")
    void jstLiteralIsSameInstant() {
        insertWithCreatedAt("2026-09-04 21:00:00+09");

        assertThat(load().getCreatedAt().toInstant())
                .isEqualTo(Instant.parse("2026-09-04T12:00:00Z"));
    }

    @Test
    @DisplayName("JVM の既定タイムゾーンを変えても、指す瞬間は変わらない")
    void instantIsIndependentOfJvmTimeZone() {
        insertWithCreatedAt("2026-09-04 12:00:00+00");
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"UTC", "Asia/Tokyo", "America/New_York"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                em.clear();
                assertThat(load().getCreatedAt().toInstant())
                        .as("JVM の既定 TZ が %s のとき", zone)
                        .isEqualTo(Instant.parse("2026-09-04T12:00:00Z"));
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("アプリが書いた created_at が、現在時刻として妥当な範囲に入る")
    void applicationWrittenTimestampIsSane() {
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        em.createNativeQuery("""
                INSERT INTO appearance (appearance_date, event_name, event_key,
                    source_url, source_type)
                VALUES (DATE '2026-09-04', 'デフォルト', 'default',
                        'https://x.com/a/status/1', 'MANUAL')
                """).executeUpdate();
        em.flush();
        em.clear();
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1);

        // DB 側の DEFAULT now() が入る。ずれていれば 1 分の窓から外れる
        assertThat(load().getCreatedAt()).isBetween(before, after);
    }
}
