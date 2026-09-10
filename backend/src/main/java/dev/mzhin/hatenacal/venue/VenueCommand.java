package dev.mzhin.hatenacal.venue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 会場の編集（docs/api.md「会場の一覧と編集」の {@code PUT}）。
 *
 * <p>長さの上限は {@code venue} テーブルの CHECK 制約と揃える
 * （docs/data-model.md「venue — 会場」）。<b>サーバ側検証を正とする</b>（NFR-03）。
 *
 * <p><b>{@code venueKey} は受け取らない。</b> 表記から機械的に決まる値であり、
 * 変えると出演情報が別の会場に化ける。
 */
public record VenueCommand(
        @NotBlank(message = "displayName は必須です")
        @Size(max = 300, message = "displayName は 300 文字以内です")
        String displayName,

        @NotNull(message = "region は必須です")
        Region region,

        // null で解決前に戻す。誤って解決した場合の取り消し
        @Size(max = 300, message = "placeId は 300 文字以内です")
        String placeId) {
}
