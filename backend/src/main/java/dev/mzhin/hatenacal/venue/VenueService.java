package dev.mzhin.hatenacal.venue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会場の引き当てと作成（ADR-0022）。
 *
 * <p><b>外部 API を呼ばない。</b> DB の中だけで完結するため、
 * 取り込みのトランザクションから呼んで安全である。
 * Google への問い合わせ（place_id の解決）は別の経路で行う。
 */
@Service
public class VenueService {

    private final VenueRepository repository;

    public VenueService(VenueRepository repository) {
        this.repository = repository;
    }

    /**
     * 会場名から会場を引き当て、無ければ作る。
     *
     * <p>表記ゆれは {@link VenueKey} が同じキーへ寄せるため、
     * {@code 大須RADHALL} と {@code 大須RAD HALL} は同じ行になる。
     *
     * <p><b>既存行の代表表記と地域を上書きしない。</b> 後から別表記の告知が来ても、
     * 最初に見た表記を代表として保つ。管理者が直した内容を巻き戻さないためでもある
     * （ADR-0004 と同じ考え方）。
     *
     * @param venueName 告知の原文。{@code null} または空なら {@code null} を返す
     */
    @Transactional
    public Venue findOrCreate(String venueName) {
        if (venueName == null || venueName.isBlank()) {
            return null;
        }
        String key = VenueKey.of(venueName);
        return repository.findByVenueKey(key)
                // 一意制約が最後の砦になる。単一インスタンス・単一スレッドの
                // 取り込みと、まれな管理操作しか書き込まないため、ここは競合しない
                .orElseGet(() -> repository.save(
                        Venue.create(key, venueName, RegionResolver.of(venueName))));
    }

    /**
     * ID から会場をまとめて引く。
     *
     * <p>一覧の DTO 化で会場ごとに引くと N+1 になる。**呼び出し側が 1 回で集める**。
     */
    @Transactional(readOnly = true)
    public Map<Long, Venue> byIds(Collection<Long> ids) {
        List<Long> present = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (present.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(present).stream()
                .collect(Collectors.toMap(Venue::getId, Function.identity()));
    }
}
