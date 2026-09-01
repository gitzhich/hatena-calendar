package dev.mzhin.hatenacal.appearance;

import java.util.List;

/** 該当がない場合も 404 にせず、空配列を返す（docs/api.md 第 4.1 節）。 */
public record PublicAppearanceListResponse(List<PublicAppearanceDto> appearances) {
}
