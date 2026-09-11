package dev.mzhin.hatenacal.appearance;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 管理者による登録・編集の入力（docs/api.md 第 5.2 / 5.3 節）。
 *
 * <p><b>eventKey はクライアントから受け取らない。</b> サーバ側で eventName から
 * 生成する（ADR-0006）。受け取れるようにすると、表示名と照合キーが
 * 食い違った行を外から作れてしまう。
 *
 * <p>サーバ側検証を正とする（NFR-03）。フロント側の検証は UX のためのもの。
 */
public record AppearanceCommand(
        @NotNull(message = "appearanceDate は必須です")
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate appearanceDate,

        @NotBlank(message = "eventName は必須です")
        @Size(max = 200, message = "eventName は 200 文字以内です") String eventName,

        @Size(max = 300, message = "venueName は 300 文字以内です") String venueName,

        /*
         * 会場が未定のときの地名（docs/api.md「編集」/ ADR-0022「会場が未定でも地域は持つ」）。
         * venueName が入っていればそちらから地域を引くので、両方を入れる必要は無い。
         */
        @Size(max = 100, message = "areaName は 100 文字以内です") String areaName,

        @JsonFormat(pattern = "HH:mm:ss") LocalTime performanceStartTime,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime performanceEndTime,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime merchStartTime,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime merchEndTime,

        @Pattern(regexp = "^https?://.*", message = "ticketUrl は http:// または https:// で始まります")
        String ticketUrl,

        // 根拠のないデータを公開しない（FR-06）。手動登録でも必須
        @NotBlank(message = "sourceUrl は必須です")
        @Pattern(regexp = "^https://.*", message = "sourceUrl は https:// で始まります")
        String sourceUrl,

        Long ingestedPostId) {
}
