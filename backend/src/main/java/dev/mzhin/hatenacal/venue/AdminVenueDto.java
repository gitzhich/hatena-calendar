package dev.mzhin.hatenacal.venue;

import java.time.OffsetDateTime;

/**
 * 管理 API のレスポンス項目（docs/api.md「会場の一覧と編集」）。
 *
 * <p>{@code venueKey} は返すが<b>編集できない</b>。表記から機械的に決まる値であり、
 * 変えると出演情報が別の会場に化ける。管理画面では照合の手がかりとして見せる。
 */
public record AdminVenueDto(
        Long id,
        String venueKey,
        String displayName,
        Region region,
        String placeId,
        OffsetDateTime placeIdCheckedAt,
        boolean manuallyEdited,
        long appearanceCount) {

    /** @param appearanceCount この会場を指す出演情報の件数。<b>直す価値の大きさが分かる</b> */
    public static AdminVenueDto from(Venue v, long appearanceCount) {
        return new AdminVenueDto(v.getId(), v.getVenueKey(), v.getDisplayName(), v.getRegion(),
                v.getPlaceId(), v.getPlaceIdCheckedAt(), v.isManuallyEdited(), appearanceCount);
    }
}
