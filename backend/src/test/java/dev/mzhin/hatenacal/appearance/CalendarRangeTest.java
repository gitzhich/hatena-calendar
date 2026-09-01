package dev.mzhin.hatenacal.appearance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-05 / ADR-0014：受け付ける年月の範囲。 */
class CalendarRangeTest {

    /** 2026-09-01 09:00 JST に固定する。 */
    private static final Clock NOW =
            Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("サービス開始月より前は範囲外")
    void beforeServiceStart() {
        assertThat(CalendarRange.contains(YearMonth.of(2025, 12), NOW)).isFalse();
        assertThat(CalendarRange.contains(YearMonth.of(2026, 1), NOW)).isTrue();
    }

    @Test
    @DisplayName("24 か月先までは範囲内、その先は範囲外")
    void futureBound() {
        assertThat(CalendarRange.contains(YearMonth.of(2028, 9), NOW)).isTrue();
        assertThat(CalendarRange.contains(YearMonth.of(2028, 10), NOW)).isFalse();
    }

    @Test
    @DisplayName("極端な年月を弾く。キャッシュキーを無制限に増やされる経路を塞ぐ")
    void absurdValues() {
        assertThat(CalendarRange.contains(YearMonth.of(1000, 1), NOW)).isFalse();
        assertThat(CalendarRange.contains(YearMonth.of(9999, 12), NOW)).isFalse();
    }

    @Test
    @DisplayName("上限は JST の「今日」で決まる。UTC で日付が変わっても JST では変わらない境界")
    void boundaryIsEvaluatedInJst() {
        // 2026-09-30 15:30 UTC = 2026-10-01 00:30 JST
        Clock utcSep30ButJstOct1 =
                Clock.fixed(Instant.parse("2026-09-30T15:30:00Z"), ZoneOffset.UTC);
        // JST では 10 月なので、上限は 2028-10 まで伸びる
        assertThat(CalendarRange.contains(YearMonth.of(2028, 10), utcSep30ButJstOct1)).isTrue();
    }

    @Test
    @DisplayName("表示できる最初と最後の日")
    void bounds() {
        assertThat(CalendarRange.firstDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(CalendarRange.lastDate(NOW)).isEqualTo(LocalDate.of(2028, 9, 30));
    }
}
