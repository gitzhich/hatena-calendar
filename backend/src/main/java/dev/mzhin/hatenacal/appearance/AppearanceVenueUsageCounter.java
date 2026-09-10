package dev.mzhin.hatenacal.appearance;

import dev.mzhin.hatenacal.venue.VenueUsageCounter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会場ごとの出演情報の件数（docs/api.md「会場の一覧と編集」の {@code appearanceCount}）。
 *
 * <p><b>venue 側にあるのは口だけで、実装はここに置く</b>（{@link VenueUsageCounter}）。
 * 依存を appearance → venue の一方向に保つため。
 */
@Component
public class AppearanceVenueUsageCounter implements VenueUsageCounter {

    private final AppearanceRepository repository;

    public AppearanceVenueUsageCounter(AppearanceRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> countByVenueIds(Collection<Long> venueIds) {
        List<Long> present = venueIds.stream().filter(Objects::nonNull).distinct().toList();
        if (present.isEmpty()) {
            // IN () は SQL として組み立てられない。呼び出し側で切る
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : repository.countByVenueIds(present)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }
}
