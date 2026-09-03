package dev.mzhin.hatenacal.ingestion;

import java.util.List;

/**
 * 取り込み履歴の応答（docs/api.md 第 5.7 節）。
 *
 * <p>ページングの共通形（{@link dev.mzhin.hatenacal.common.PageResponse}）に
 * 集計と警告を足した形のため、record では継承できず同じ 4 つの列を持つ。
 *
 * @param currentMonthResourceCount 当月に取得したリソース数の合計。X API の課金単位そのもので、
 *     {@code × $0.005} が概算コストになる（NFR-04）
 * @param consecutiveFailureCount 直近で失敗が連続している回数
 * @param halted 連続失敗で取り込みが打ち切られているか。<b>判定はサーバ側で行う</b>。
 *     実際に止める条件と表示する条件を 1 か所に集約するため（NFR-09 / {@link IngestionHaltRule}）
 */
public record IngestionRunListResponse(
        List<IngestionRunDto> items,
        int page,
        int size,
        long totalElements,
        long currentMonthResourceCount,
        int consecutiveFailureCount,
        boolean halted) {
}
