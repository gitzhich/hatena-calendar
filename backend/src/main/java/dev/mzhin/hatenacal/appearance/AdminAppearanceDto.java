package dev.mzhin.hatenacal.appearance;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * 管理 API のレスポンス項目（docs/api.md 第 5.1 節）。
 *
 * <p>公開 API と違い内部項目も返す。点検（FR-24）では eventKey や
 * 登録経路が判断材料になるため。
 */
public record AdminAppearanceDto(
        Long id,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate appearanceDate,
        String eventName,
        String eventKey,
        String venueName,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime performanceStartTime,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime performanceEndTime,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime merchStartTime,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime merchEndTime,
        String ticketUrl,
        String sourceUrl,
        SourceType sourceType,
        Long ingestedPostId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static AdminAppearanceDto from(Appearance a) {
        return new AdminAppearanceDto(a.getId(), a.getAppearanceDate(), a.getEventName(),
                a.getEventKey(), a.getVenueName(),
                a.getPerformanceStartTime(), a.getPerformanceEndTime(),
                a.getMerchStartTime(), a.getMerchEndTime(),
                a.getTicketUrl(), a.getSourceUrl(), a.getSourceType(),
                a.getIngestedPostId(), a.getCreatedAt(), a.getUpdatedAt());
    }
}
