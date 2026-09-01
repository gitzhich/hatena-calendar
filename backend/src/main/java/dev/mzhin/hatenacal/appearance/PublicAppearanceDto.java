package dev.mzhin.hatenacal.appearance;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 公開 API のレスポンス項目（docs/api.md 第 4.1 節）。
 *
 * <p><b>eventKey / sourceType / createdAt / ingestedPostId は返さない。</b>
 * 内部の実装詳細であり、閲覧者に出す値ではない。
 *
 * <p>日付と時刻に<b>タイムゾーン情報を付けない</b>（同 第 3.2 節）。
 * これらは特定の瞬間ではなく暦日・ローカル時刻で、
 * オフセットを付けるとクライアント側の変換で日付がずれる。
 */
public record PublicAppearanceDto(
        Long id,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate appearanceDate,
        String eventName,
        String venueName,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime performanceStartTime,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime performanceEndTime,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime merchStartTime,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime merchEndTime,
        String ticketUrl,
        String sourceUrl) {

    public static PublicAppearanceDto from(Appearance a) {
        return new PublicAppearanceDto(
                a.getId(),
                a.getAppearanceDate(),
                a.getEventName(),
                a.getVenueName(),
                a.getPerformanceStartTime(),
                a.getPerformanceEndTime(),
                a.getMerchStartTime(),
                a.getMerchEndTime(),
                a.getTicketUrl(),
                a.getSourceUrl());
    }
}
