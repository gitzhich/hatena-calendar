package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 取り込み履歴の読み取りのうち、DB を通さずに固定できる部分
 * （docs/api.md「取り込み履歴」、NFR-04 / NFR-05）。
 *
 * <p>日時の UTC 正規化をここに置く。PostgreSQL の TIMESTAMPTZ を
 * OffsetDateTime で読むとドライバが UTC で返すため、結合テストでは正規化を外しても
 * 結果が変わらない（FR-08 で実際に変異が素通りした。{@link IngestionStatusTest} 参照）。
 *
 * <p>集計期間の区切りは {@link BillingCycleTest}。
 */
class IngestionRunQueryTest {

    private static final ZoneOffset JST = ZoneOffset.ofHours(9);

    @Test
    @DisplayName("DTO は日時を UTC に正規化する（docs/api.md「取り込み履歴」）")
    void dtoNormalizesToUtc() {
        OffsetDateTime started = OffsetDateTime.of(2026, 9, 3, 10, 0, 0, 0, JST);
        OffsetDateTime finished = OffsetDateTime.of(2026, 9, 3, 10, 0, 3, 0, JST);

        IngestionRunDto dto = new IngestionRunDto(
                1L, started, finished, IngestionRunStatus.SUCCESS, 4, 1, 2, false, null);

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
                IngestionRunStatus.RUNNING, 0, 0, null, false, null);

        assertThat(dto.finishedAt()).isNull();
    }
}
