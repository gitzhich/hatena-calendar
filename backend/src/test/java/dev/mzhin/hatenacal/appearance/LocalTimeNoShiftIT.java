package dev.mzhin.hatenacal.appearance;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.TimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * DATE / TIME 列にタイムゾーン変換がかからないことの回帰テスト。
 *
 * <p><b>値を SQL リテラルで書き、JPA で読む。</b> パラメータバインドで書くと
 * 書き込みと読み出しで同じ変換がかかって<b>ズレが相殺され、テストが通ってしまう</b>。
 * 実際にこの見逃しが起きた（hibernate.jdbc.time_zone=UTC を入れていたとき、
 * DB の 21:05 が API では 06:05 になっていたが、往復テストは緑だった）。
 *
 * <p>イベントの開催日・出演時刻は JST のローカル値で、UTC へ変換しない
 * （docs/data-model.md「タイムゾーンの扱い」 / ADR-0005）。ここが狂うとカレンダーの
 * 日付そのものがずれる。
 */
@SpringBootTest
@Transactional
class LocalTimeNoShiftIT {

    @Autowired
    private AppearanceRepository repository;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void clean() {
        em.createNativeQuery("DELETE FROM appearance").executeUpdate();
    }

    /** 値をリテラルで埋め込む。バインドを通さないので相殺が起きない。 */
    private void insertLiteral(String date, String start, String end) {
        em.createNativeQuery("""
                INSERT INTO appearance (appearance_date, event_name, event_key,
                    performance_start_time, performance_end_time, source_url, source_type)
                VALUES (DATE '%s', 'テスト', 'test',
                        TIME '%s', TIME '%s', 'https://x.com/a/status/1', 'MANUAL')
                """.formatted(date, start, end)).executeUpdate();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("DB に書いた 21:05 が 21:05 のまま読める。+9 時間ずれない")
    void timeIsNotShifted() {
        insertLiteral("2026-09-04", "21:05:00", "21:30:00");

        Appearance a = repository.findForCalendar(
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4)).get(0);

        assertThat(a.getPerformanceStartTime()).isEqualTo(LocalTime.of(21, 5));
        assertThat(a.getPerformanceEndTime()).isEqualTo(LocalTime.of(21, 30));
    }

    @Test
    @DisplayName("深夜帯の 02:00 が前日の 17:00 に化けない")
    void midnightTimeIsNotShifted() {
        insertLiteral("2026-09-17", "02:00:00", "02:30:00");

        Appearance a = repository.findForCalendar(
                LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 17)).get(0);

        assertThat(a.getAppearanceDate()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(a.getPerformanceStartTime()).isEqualTo(LocalTime.of(2, 0));
    }

    @Test
    @DisplayName("月初 00:00 が前月末に落ちない。月境界で最も危険なケース")
    void firstDayMidnightStaysInMonth() {
        insertLiteral("2026-09-01", "00:00:00", "00:30:00");

        assertThat(repository.findForCalendar(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .hasSize(1);
        assertThat(repository.findForCalendar(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .isEmpty();
    }

    @Test
    @DisplayName("JVM の既定タイムゾーンが何であれ結果が変わらない")
    void independentOfJvmTimeZone() {
        insertLiteral("2026-09-04", "21:05:00", "21:30:00");
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"UTC", "Asia/Tokyo", "America/New_York"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                em.clear();
                Appearance a = repository.findForCalendar(
                        LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4)).get(0);
                assertThat(a.getPerformanceStartTime())
                        .as("JVM の既定 TZ が %s のとき", zone)
                        .isEqualTo(LocalTime.of(21, 5));
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
