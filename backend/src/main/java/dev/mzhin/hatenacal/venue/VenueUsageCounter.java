package dev.mzhin.hatenacal.venue;

import java.util.Collection;
import java.util.Map;

/**
 * 会場を参照している出演情報の件数（docs/api.md「会場の一覧と編集」の {@code appearanceCount}）。
 *
 * <p><b>実装は appearance 側に置き、依存の向きを保つ。</b> appearance は venue を
 * 知っている（{@code Appearance.venueId} / {@code AdminAppearanceDto}）。ここから
 * appearance を直接呼ぶと双方向になり、どちらが土台か分からなくなる。
 * 件数の求め方を知っているのは appearance なので、口だけをこちらに置く。
 */
public interface VenueUsageCounter {

    /**
     * @return 会場 ID → 件数。<b>0 件の会場は現れない</b>ので、呼び出し側が既定 0 を補う
     */
    Map<Long, Long> countByVenueIds(Collection<Long> venueIds);
}
