package dev.mzhin.hatenacal.ingestion;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 取り込み実行 1 回分（docs/api.md「取り込み履歴」、FR-42 / NFR-09）。
 *
 * <p><b>日時は UTC で返す。</b> API 契約が UTC と定めており、JST への変換は表示側の責務
 * （docs/data-model.md「タイムゾーンの扱い」）。DB から読んだ値をそのまま載せると、JDBC ドライバが
 * 返すオフセット（JVM の既定タイムゾーンに依存する）がそのまま JSON に出る。
 *
 * <p>{@code errorSummary} には<b>スタックトレースとトークンが入らない</b>（NFR-03）。
 * 入れないことは {@link IngestionRun#fail} の呼び出し側が担保する。
 *
 * <p>{@code truncated} は<b>成功した実行に付く注記</b>。ページ上限で打ち切ったため
 * 古い投稿を取りこぼしたことを表す（docs/x-integration.md「ページング」 / ADR-0020）。
 */
public record IngestionRunDto(
        Long id,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        IngestionRunStatus status,
        int fetchedResourceCount,
        int newAppearanceCount,
        Integer unparsedCount,
        boolean truncated,
        String errorSummary) {

    public IngestionRunDto {
        startedAt = toUtc(startedAt);
        finishedAt = toUtc(finishedAt);
    }

    private static OffsetDateTime toUtc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    static IngestionRunDto from(IngestionRun run) {
        return new IngestionRunDto(
                run.getId(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getStatus(),
                run.getFetchedResourceCount(),
                run.getNewAppearanceCount(),
                run.getUnparsedCount(),
                run.isTruncated(),
                run.getErrorSummary());
    }
}
