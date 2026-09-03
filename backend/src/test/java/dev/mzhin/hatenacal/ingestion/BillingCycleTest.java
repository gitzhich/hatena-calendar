package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 請求サイクルの区切り（NFR-04 / NFR-05、docs/api.md 第 5.7 節）。
 *
 * <p><b>実時刻に依存させずに確かめる。</b> 境界の前後 30 分という条件は実行日を選ぶため、
 * 結合テストでは狙って踏めない。
 */
class BillingCycleTest {

    private static final ZoneOffset JST = ZoneOffset.ofHours(9);

    private static OffsetDateTime startOf(OffsetDateTime now, int day) {
        return BillingCycle.startOf(now, day);
    }

    private static OffsetDateTime jst(int y, int m, int d, int h, int min) {
        return OffsetDateTime.of(y, m, d, h, min, 0, 0, JST);
    }

    @Test
    @DisplayName("起点 1 は暦月と一致する。特殊ケースであって別の分岐ではない")
    void dayOneMatchesCalendarMonth() {
        assertThat(startOf(jst(2026, 9, 15, 12, 0), 1).toInstant())
                .isEqualTo(jst(2026, 9, 1, 0, 0).toInstant());
    }

    @Test
    @DisplayName("起点の当日 0 時ちょうどは新しいサイクルに入る")
    void startDayItselfBeginsTheCycle() {
        assertThat(startOf(jst(2026, 9, 2, 0, 0), 2).toInstant())
                .isEqualTo(jst(2026, 9, 2, 0, 0).toInstant());
    }

    @Test
    @DisplayName("起点の前日 23 時 30 分はまだ前のサイクル")
    void justBeforeTheStartDayStaysInThePreviousCycle() {
        assertThat(startOf(jst(2026, 9, 1, 23, 30), 2).toInstant())
                .isEqualTo(jst(2026, 8, 2, 0, 0).toInstant());
    }

    @Test
    @DisplayName("境界は JST で切る。UTC で切ると 9 時間分が前のサイクルに混じる")
    void boundaryIsCutInJst() {
        // この瞬間は UTC ではまだ 9/1 15:30。UTC で切ると 8/2 起点になる
        OffsetDateTime now = jst(2026, 9, 2, 0, 30);

        assertThat(now.withOffsetSameInstant(ZoneOffset.UTC).getDayOfMonth())
                .as("前提の確認：この瞬間は UTC ではまだ 9/1")
                .isEqualTo(1);
        assertThat(startOf(now, 2).toInstant())
                .isEqualTo(jst(2026, 9, 2, 0, 0).toInstant());
    }

    @Test
    @DisplayName("入力のオフセットが何であれ、同じ瞬間なら同じ起点になる")
    void resultDoesNotDependOnInputOffset() {
        OffsetDateTime now = jst(2026, 9, 2, 0, 30);

        assertThat(startOf(now.withOffsetSameInstant(ZoneOffset.UTC), 2).toInstant())
                .isEqualTo(startOf(now, 2).toInstant());
    }

    @Test
    @DisplayName("年をまたぐと前年の 12 月起点になる")
    void cycleCrossesTheYear() {
        assertThat(startOf(jst(2027, 1, 1, 8, 0), 2).toInstant())
                .isEqualTo(jst(2026, 12, 2, 0, 0).toInstant());
    }

    @Test
    @DisplayName("起点 31 の 2 月は月末に丸める")
    void clampsToTheEndOfShortMonths() {
        // 2/31 は存在しない。1/31 起点のサイクルが 2 月いっぱい続く
        assertThat(startOf(jst(2026, 2, 15, 12, 0), 31).toInstant())
                .isEqualTo(jst(2026, 1, 31, 0, 0).toInstant());
    }

    @Test
    @DisplayName("起点 31 の 3 月 30 日は、丸められた 2 月末が起点になる")
    void previousMonthIsAlsoClamped() {
        assertThat(startOf(jst(2026, 3, 30, 12, 0), 31).toInstant())
                .isEqualTo(jst(2026, 2, 28, 0, 0).toInstant());
    }

    @Test
    @DisplayName("範囲外の起点は起動時に落とす。黙って別の期間を数えない")
    void rejectsOutOfRangeStartDay() {
        assertThatThrownBy(() -> startOf(jst(2026, 9, 15, 12, 0), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> startOf(jst(2026, 9, 15, 12, 0), 32))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
