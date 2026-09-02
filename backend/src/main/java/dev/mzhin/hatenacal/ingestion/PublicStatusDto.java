package dev.mzhin.hatenacal.ingestion;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * データの鮮度（docs/api.md 第 4.2 節、FR-08）。
 *
 * <p><b>日時は UTC で返す。</b> API 契約が UTC と定めており、JST への変換は表示側の責務
 * （docs/data-model.md 第 6 章）。DB から読んだ値をそのまま載せると、JDBC ドライバが
 * 返すオフセット（JVM の既定タイムゾーンに依存する）がそのまま JSON に出るため、
 * ここで正規化する。絶対時刻としては同じなので、ずれるのは表記だけ。
 *
 * <p>一度も成功していなければ {@code lastSuccessfulIngestionAt} は null。
 */
public record PublicStatusDto(OffsetDateTime lastSuccessfulIngestionAt, boolean stale) {

    public PublicStatusDto {
        if (lastSuccessfulIngestionAt != null) {
            lastSuccessfulIngestionAt = lastSuccessfulIngestionAt.withOffsetSameInstant(ZoneOffset.UTC);
        }
    }
}
