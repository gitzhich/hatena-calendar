package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 取り込み履歴の読み取りのうち、DB を通さずに固定できる部分
 * （docs/api.md 第 5.7 節、NFR-04 / NFR-05）。
 *
 * <p>当月の区切りは<b>実時刻に依存させずに</b>確かめる必要がある。月境界の前後 30 分という
 * 条件は実行日を選ぶため、結合テストでは狙って踏めない。
 *
 * <p>日時の UTC 正規化も同じ理由でここに置く。PostgreSQL の TIMESTAMPTZ を
 * OffsetDateTime で読むとドライバが UTC で返すため、結合テストでは正規化を外しても
 * 結果が変わらない（FR-08 で実際に変異が素通りした。{@link IngestionStatusTest} 参照）。
 */
class IngestionRunQueryTest {

    private static final ZoneOffset JST = ZoneOffset.ofHours(9);

    private static OffsetDateTime monthStartAt(OffsetDateTime now) {
        return IngestionRunQueryService.currentMonthStart(now);
    }

    @Test
    @DisplayName("当月の起点は JST の月初 0 時")
    void monthStartIsJstMidnight() {
        OffsetDateTime start = monthStartAt(OffsetDateTime.of(2026, 9, 15, 12, 0, 0, 0, JST));

        assertThat(start.toInstant())
                .isEqualTo(OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, JST).toInstant());
    }

    @Test
    @DisplayName("JST の月初 0 時 30 分は当月に入る。UTC で切ると前月起点になる")
    void justAfterJstMonthStartBelongsToTheNewMonth() {
        // この瞬間は UTC ではまだ 8/31 15:30。暦月を UTC で切ると 8 月起点になり、
        // 管理画面の「当月の消費」に前月分が混ざる
        OffsetDateTime now = OffsetDateTime.of(2026, 9, 1, 0, 30, 0, 0, JST);

        assertThat(now.withOffsetSameInstant(ZoneOffset.UTC).getMonthValue())
                .as("前提の確認：この瞬間は UTC ではまだ 8 月")
                .isEqualTo(8);
        assertThat(monthStartAt(now).toInstant())
                .isEqualTo(OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, JST).toInstant());
    }

    @Test
    @DisplayName("JST の月末 23 時 30 分はまだ当月。翌月に繰り上がらない")
    void justBeforeJstMonthEndStaysInTheMonth() {
        OffsetDateTime now = OffsetDateTime.of(2026, 8, 31, 23, 30, 0, 0, JST);

        assertThat(monthStartAt(now).toInstant())
                .isEqualTo(OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, JST).toInstant());
    }

    @Test
    @DisplayName("入力のオフセットが何であれ、同じ瞬間なら同じ起点になる")
    void resultDoesNotDependOnInputOffset() {
        OffsetDateTime jst = OffsetDateTime.of(2026, 9, 1, 0, 30, 0, 0, JST);

        assertThat(monthStartAt(jst.withOffsetSameInstant(ZoneOffset.UTC)).toInstant())
                .isEqualTo(monthStartAt(jst).toInstant());
    }

    @Test
    @DisplayName("年をまたぐ月初も 1 月起点になる")
    void januaryStartCrossesTheYear() {
        OffsetDateTime now = OffsetDateTime.of(2027, 1, 1, 8, 0, 0, 0, JST);

        assertThat(monthStartAt(now).toInstant())
                .isEqualTo(OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, JST).toInstant());
    }

    @Test
    @DisplayName("DTO は日時を UTC に正規化する（docs/api.md 第 5.7 節）")
    void dtoNormalizesToUtc() {
        OffsetDateTime started = OffsetDateTime.of(2026, 9, 3, 10, 0, 0, 0, JST);
        OffsetDateTime finished = OffsetDateTime.of(2026, 9, 3, 10, 0, 3, 0, JST);

        IngestionRunDto dto = new IngestionRunDto(
                1L, started, finished, IngestionRunStatus.SUCCESS, 4, 1, false, null);

        assertThat(dto.startedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(dto.finishedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(dto.startedAt().toInstant())
                .as("正規化で瞬間そのものを動かしてはいけない")
                .isEqualTo(started.toInstant());
        assertThat(dto.finishedAt().toInstant()).isEqualTo(finished.toInstant());
    }

    @Test
    @DisplayName("実行中の記録は finishedAt が null のまま")
    void dtoKeepsNullFinishedAt() {
        IngestionRunDto dto = new IngestionRunDto(
                1L, OffsetDateTime.now(ZoneOffset.UTC), null,
                IngestionRunStatus.RUNNING, 0, 0, false, null);

        assertThat(dto.finishedAt()).isNull();
    }
}
