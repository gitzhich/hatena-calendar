package dev.mzhin.hatenacal.appearance;

import java.util.List;

/** 該当がない場合も 404 にせず、空配列を返す（docs/api.md「期間内の出演情報一覧」）。 */
public record PublicAppearanceListResponse(List<PublicAppearanceDto> appearances) {
}
