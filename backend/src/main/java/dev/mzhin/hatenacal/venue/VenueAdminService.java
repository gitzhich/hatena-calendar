package dev.mzhin.hatenacal.venue;

import dev.mzhin.hatenacal.common.ConflictException;
import dev.mzhin.hatenacal.common.NotFoundException;
import dev.mzhin.hatenacal.common.PageResponse;
import dev.mzhin.hatenacal.common.UpstreamException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
     * <p><b>並び順はクエリ側で決める</b>（{@link VenueRepository#REGION_ORDER}）。
     * 地方でまとまるので、同じ地域の会場を見比べながら直せる。
     *
     * @param unresolved {@code true} なら place_id が未解決の会場だけ。
     *                   初期投入の進み具合と、解決できない会場の確認に使う
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminVenueDto> list(boolean unresolved, Pageable pageable) {
        Page<Venue> page = unresolved
                ? repository.findUnresolvedOrdered(pageable)
                : repository.findAllOrdered(pageable);
        Map<Long, Long> counts = countsFor(page.getContent());
        return PageResponse.of(page, v -> AdminVenueDto.from(v, count(counts, v)));
    }

    /**
     * 1 件（docs/api.md「会場の一覧と編集」）。編集画面が現在値を読むために使う。
     *
     * <p><b>一覧の 1 要素と同じ形を返す。</b> 画面が一覧と詳細で別の形を扱わずに済む。
     */
    @Transactional(readOnly = true)
    public AdminVenueDto find(Long id) {
        return detail(load(id));
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
     * 削除（docs/api.md「会場の一覧と編集」）。
     *
     * <p><b>出演情報から参照されていない行だけ消せる。</b> 参照されている会場を消すと
     * 地域も地図リンクも一緒に失われ、公開ページから会場の表示が落ちる。
     * 消したいのは会場名の書き換えで取り残された行（{@code appearanceCount} が 0）だけ。
     *
     * <p><b>数えてから消すまでの間に増える。</b> 取り込みの定期実行が 30 分ごとに
     * {@code venue_id} を付けるため（ADR-0022「既存データの初期投入」）、件数が 0 でも
     * 削除の直前に紐づくことがある。**外部キー違反も 409 に落とす。** 500 として
     * 素通しにすると、防げた衝突が「サーバ内部エラー」に見える。
     *
     * <p><b>{@code manually_edited} でも消せる。</b> 条件は使用件数だけ。ただし
     * 人が直した地域も一緒に消えるので、同じ表記が再び告知に出れば自動判定で
     * 作り直される。出演 0 件の行に限られるため、公開されている情報は変わらない。
     */
    @Transactional
    public void delete(Long id) {
        Venue target = load(id);
        long used = count(countsFor(List.of(target)), target);
        if (used > 0) {
            throw new ConflictException("出演情報が " + used + " 件あるため削除できません");
        }
        try {
            repository.delete(target);
            // ここで流し込まないと DELETE はコミット時まで遅れ、catch の外で落ちる
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("削除の直前に出演情報が紐づいた（venue={}）", id);
            throw new ConflictException("削除の直前に出演情報が紐づいたため削除できません");
        }
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
