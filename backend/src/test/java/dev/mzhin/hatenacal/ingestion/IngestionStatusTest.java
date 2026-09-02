package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 鮮度判定の境界（FR-08 / docs/api.md 第 4.2 節）。
 *
 * <p>「24 時間以上」の境界をここで固定する。DB も HTTP も通さないので、
 * 実時刻に依存せず 1 秒刻みで確かめられる。
 */
class IngestionStatusTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 2, 12, 0, 0, 0, ZoneOffset.UTC);

    private static boolean staleAfter(long hours, long seconds) {
        return IngestionStatusService.isStale(NOW.minusHours(hours).minusSeconds(seconds), NOW);
    }

    @Test
    @DisplayName("24 時間ちょうどは stale。FR-08 は「24 時間以上」")
    void exactlyTwentyFourHoursIsStale() {
        assertThat(staleAfter(24, 0)).isTrue();
    }

    @Test
    @DisplayName("23 時間 59 分 59 秒は stale ではない")
    void justUnderThresholdIsFresh() {
        assertThat(staleAfter(23, 3599)).isFalse();
    }

    @Test
    @DisplayName("24 時間を 1 秒でも超えれば stale")
    void justOverThresholdIsStale() {
        assertThat(staleAfter(24, 1)).isTrue();
    }

    @Test
    @DisplayName("直後は stale ではない")
    void justFinishedIsFresh() {
        assertThat(staleAfter(0, 0)).isFalse();
    }

    @Test
    @DisplayName("一度も成功していなければ stale ではない（警告は日時に併記するもの）")
    void neverSucceededIsNotStale() {
        assertThat(IngestionStatusService.isStale(null, NOW)).isFalse();
    }

    @Test
    @DisplayName("最終成功が未来でも stale にしない。時計のずれで警告を出さない")
    void futureTimestampIsNotStale() {
        assertThat(IngestionStatusService.isStale(NOW.plusHours(1), NOW)).isFalse();
    }

    @Test
    @DisplayName("判定はオフセットに依存しない。同じ瞬間なら同じ結果")
    void offsetDoesNotAffectComparison() {
        // JST 表記の「24 時間前」。UTC 表記のときと同じ判定にならなければ、
        // どこかで暦上の見た目を比べている（docs/data-model.md 第 6 章）
        OffsetDateTime jst = NOW.minusHours(24).withOffsetSameInstant(ZoneOffset.ofHours(9));
        assertThat(IngestionStatusService.isStale(jst, NOW)).isTrue();

        OffsetDateTime jstFresh = NOW.minusHours(23).withOffsetSameInstant(ZoneOffset.ofHours(9));
        assertThat(IngestionStatusService.isStale(jstFresh, NOW)).isFalse();
    }
}
