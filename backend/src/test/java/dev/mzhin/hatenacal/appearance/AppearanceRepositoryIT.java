package dev.mzhin.hatenacal.appearance;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 実 DB に対する検証。日付境界は最も壊れやすいので必ず書く
 * （CLAUDE.md 開発上の注意 / NFR-05）。
 */
@SpringBootTest
@Transactional
class AppearanceRepositoryIT {

    @Autowired
    private AppearanceRepository repository;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void clean() {
        em.createNativeQuery("DELETE FROM appearance").executeUpdate();
    }

    private void insert(LocalDate date, String name, String key, LocalTime start, String venue) {
        em.createNativeQuery("""
                INSERT INTO appearance (appearance_date, event_name, event_key, venue_name,
                    performance_start_time, source_url, source_type)
                VALUES (?, ?, ?, ?, ?, 'https://x.com/a/status/1', 'MANUAL')
                """)
                .setParameter(1, date)
                .setParameter(2, name)
                .setParameter(3, key)
                .setParameter(4, venue)
                .setParameter(5, start)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("暦日がそのまま往復する。UTC 変換で前日にずれない（ADR-0005）")
    void calendarDateRoundTrips() {
        // 月初 00:00 JST は UTC だと前月末。UTC 化していれば 7 月に落ちる
        insert(LocalDate.of(2026, 8, 1), "月初", "gessho", LocalTime.of(0, 0), "東京");
        insert(LocalDate.of(2026, 8, 31), "月末", "getsumatsu", LocalTime.of(23, 30), "東京");

        List<Appearance> august = repository.findForCalendar(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(august).extracting(Appearance::getAppearanceDate)
                .containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
    }

    @Test
    @DisplayName("月またぎ：隣月の出演が混ざらない")
    void monthBoundary() {
        insert(LocalDate.of(2026, 7, 31), "7月末", "july", LocalTime.of(20, 0), "東京");
        insert(LocalDate.of(2026, 8, 1), "8月初", "aug1", LocalTime.of(20, 0), "東京");
        insert(LocalDate.of(2026, 8, 31), "8月末", "aug31", LocalTime.of(20, 0), "東京");
        insert(LocalDate.of(2026, 9, 1), "9月初", "sep", LocalTime.of(20, 0), "東京");

        List<Appearance> august = repository.findForCalendar(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(august).extracting(Appearance::getEventName)
                .containsExactly("8月初", "8月末");
    }

    @Test
    @DisplayName("深夜公演：26:00 は翌日の 02:00 として翌日側に並ぶ（ADR-0011）")
    void midnightPerformance() {
        // 「9/16(火) 26:00 開演」は 9/17 の 02:00 として保存する
        insert(LocalDate.of(2026, 9, 16), "夜公演", "yoru", LocalTime.of(20, 0), "東京");
        insert(LocalDate.of(2026, 9, 17), "深夜公演", "shinya", LocalTime.of(2, 0), "東京");

        List<Appearance> sep16 = repository.findForCalendar(
                LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 16));
        List<Appearance> sep17 = repository.findForCalendar(
                LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 17));

        assertThat(sep16).extracting(Appearance::getEventName).containsExactly("夜公演");
        assertThat(sep17).extracting(Appearance::getEventName).containsExactly("深夜公演");
        assertThat(sep17.get(0).getPerformanceStartTime()).isEqualTo(LocalTime.of(2, 0));
    }

    @Test
    @DisplayName("年跨ぎ：12 月末から 1 月初にかけて取れる")
    void yearBoundary() {
        insert(LocalDate.of(2026, 12, 31), "大晦日", "omisoka", LocalTime.of(22, 0), "東京");
        insert(LocalDate.of(2027, 1, 1), "元日", "ganjitsu", LocalTime.of(18, 0), "東京");

        List<Appearance> span = repository.findForCalendar(
                LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 10));

        assertThat(span).extracting(Appearance::getEventName)
                .containsExactly("大晦日", "元日");
    }

    @Test
    @DisplayName("並び順：日付昇順 → 開始時刻昇順。時刻未設定は同じ日付の末尾（FR-03）")
    void ordering() {
        insert(LocalDate.of(2026, 8, 25), "未定", "mitei", null, "東京");
        insert(LocalDate.of(2026, 8, 25), "夜", "yoru", LocalTime.of(19, 50), "東京");
        insert(LocalDate.of(2026, 8, 25), "昼", "hiru", LocalTime.of(16, 35), "東京");
        insert(LocalDate.of(2026, 8, 24), "前日", "zenjitsu", LocalTime.of(23, 0), "東京");

        List<Appearance> rows = repository.findForCalendar(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(rows).extracting(Appearance::getEventName)
                .containsExactly("前日", "昼", "夜", "未定");
    }

    @Test
    @DisplayName("同じ日・同じイベントの 2 枠が別の行として取れる（6.txt / ADR-0012）")
    void multipleSlotsSameEvent() {
        insert(LocalDate.of(2026, 8, 25), "『DERAX JAM』", "deraxjam",
                LocalTime.of(16, 35), "愛知・NAGOYA ReNY limited");
        insert(LocalDate.of(2026, 8, 25), "『DERAX JAM』", "deraxjam",
                LocalTime.of(19, 50), "愛知・RADHALL");

        List<Appearance> rows = repository.findForCalendar(
                LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 25));

        assertThat(rows).hasSize(2)
                .extracting(Appearance::getVenueName)
                .containsExactly("愛知・NAGOYA ReNY limited", "愛知・RADHALL");
    }

    @Test
    @DisplayName("該当がなければ空。例外にしない")
    void emptyResult() {
        assertThat(repository.findForCalendar(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))).isEmpty();
    }
}
