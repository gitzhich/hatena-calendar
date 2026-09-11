package dev.mzhin.hatenacal.venue;

import dev.mzhin.hatenacal.common.ConflictException;
import dev.mzhin.hatenacal.common.NotFoundException;
import dev.mzhin.hatenacal.common.PageResponse;
import dev.mzhin.hatenacal.common.UpstreamException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会場の一覧と編集（docs/api.md「会場の一覧と編集」/ FR-10）。
 *
 * <p><b>自動判定は必ず外れる。</b> 金沢のような市名、韓国のような国名、未知の会場名が
 * それに当たる。{@code venue} は 1 会場 1 行なので、<b>1 行直せば過去の全出演に効く</b>
 * （ADR-0022）。
 */
@Service
public class VenueAdminService {

    private static final Logger log = LoggerFactory.getLogger(VenueAdminService.class);

    private final VenueRepository repository;
    private final VenueUsageCounter usage;
    private final PlaceIdResolutionService resolution;

    public VenueAdminService(VenueRepository repository, VenueUsageCounter usage,
            PlaceIdResolutionService resolution) {
        this.repository = repository;
        this.usage = usage;
        this.resolution = resolution;
    }

    /**
     * 一覧。
     *
     * @param unresolved {@code true} なら place_id が未解決の会場だけ。
     *                   初期投入の進み具合と、解決できない会場の確認に使う
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminVenueDto> list(boolean unresolved, Pageable pageable) {
        Page<Venue> page = unresolved
                ? repository.findByPlaceIdIsNull(pageable)
                : repository.findAll(pageable);
        Map<Long, Long> counts = countsFor(page.getContent());
        return PageResponse.of(page, v -> AdminVenueDto.from(v, count(counts, v)));
    }

    /**
     * 訂正（docs/api.md「会場の一覧と編集」）。
     *
     * <p><b>更新すると manuallyEdited が立つ。</b> 以後、自動判定も自動解決も
     * この行を上書きしない。誤った地図リンクはリンクが無いより悪く、
     * ファンが違う場所へ向かう（docs/security.md T-08）。
     */
    @Transactional
    public AdminVenueDto update(Long id, VenueCommand cmd) {
        Venue target = load(id);
        target.editByAdmin(cmd.displayName(), cmd.region(), blankToNull(cmd.placeId()));
        return detail(target);
    }

    /**
     * 今すぐ 1 件だけ解決する（docs/api.md「会場の一覧と編集」）。
     *
     * <p><b>定常運用でこれを叩く必要は無い。</b> 解決は 1 日 1 回の定期実行が自動で進める。
     * これは再試行の間隔（7 日）を待たずに試したいときのためにある。
     *
     * <p><b>トランザクションを張らない。</b> 中で Google を呼ぶため、応答を待つ間
     * DB の接続を握らない（{@link PlaceIdResolutionService}）。
     */
    public AdminVenueDto resolvePlaceId(Long id) {
        Venue target = load(id);
        if (target.isAreaOnly()) {
            // 会場ではないので同定できない。「東京」で検索させると無関係な場所に当たる
            throw new ConflictException("地域だけの行は会場ではないため解決できません");
        }
        if (target.isManuallyEdited()) {
            throw new ConflictException("管理者が編集した会場は自動解決の対象外です");
        }
        try {
            resolution.resolveOne(target);
        } catch (PlacesException e) {
            // 相手の応答をレスポンスに含めない（NFR-03）。理由はログにだけ残す
            log.warn("place_id の解決に失敗した（venue={}）: {}", id, e.getMessage());
            throw new UpstreamException("会場の検索に失敗しました。時間をおいて再試行してください");
        }
        // 記録後の状態を返す。見つからなければ placeId は null のまま、
        // placeIdCheckedAt だけが進む
        return detail(load(id));
    }

    private AdminVenueDto detail(Venue target) {
        return AdminVenueDto.from(target, count(countsFor(List.of(target)), target));
    }

    private Map<Long, Long> countsFor(List<Venue> venues) {
        return usage.countByVenueIds(venues.stream().map(Venue::getId).toList());
    }

    /** 出演情報が 1 件も無い会場は集計に現れない。削除で 0 件になった会場が該当する。 */
    private static long count(Map<Long, Long> counts, Venue venue) {
        return counts.getOrDefault(venue.getId(), 0L);
    }

    /**
     * 空文字を {@code null} に寄せる。
     *
     * <p>フォームは未入力を空文字で送る。そのまま入れると「解決済みだが ID が空」という、
     * どちらとも取れない行ができる（docs/data-model.md「venue — 会場」）。
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Venue load(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("会場が見つかりません: id=" + id));
    }
}
