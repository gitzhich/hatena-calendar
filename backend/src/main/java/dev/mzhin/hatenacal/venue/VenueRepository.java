package dev.mzhin.hatenacal.venue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 会場（docs/data-model.md「venue — 会場」）。 */
public interface VenueRepository extends JpaRepository<Venue, Long> {

    Optional<Venue> findByVenueKey(String venueKey);

    /**
     * place_id が未解決で、再試行の間隔が空いた会場（ADR-0022「暴走と無駄叩きを防ぐ」）。
     *
     * <p><b>地域だけの行（area_only）を除く。</b> 会場ではないので同定できない。
     * 「東京」で検索させると無関係な場所に当たる（docs/security.md T-08）。
     *
     * <p><b>manually_edited の行を除く。</b> 人が確認した値を機械が触らない。
     * 管理者が誤った place_id を消した行を、翌日また同じ値で埋め直さないためでもある
     * （docs/api.md「会場の一覧と編集」）。
     *
     * <p><b>まだ一度も試していない行を先に返す。</b> 初期投入が先に進み、
     * 見つからなかった行の再試行に押しのけられない。
     *
     * <p>件数の上限は呼び出し側が {@link Pageable} で渡す。外部 API を叩くため、
     * 紐づけ（{@code findNeedingVenueLink}）と違って残りが尽きるまで繰り返さない。
     */
    @Query("""
            SELECT v FROM Venue v
             WHERE v.placeId IS NULL
               AND v.manuallyEdited = false
               AND v.areaOnly = false
               AND (v.placeIdCheckedAt IS NULL OR v.placeIdCheckedAt < :retryBefore)
             ORDER BY CASE WHEN v.placeIdCheckedAt IS NULL THEN 0 ELSE 1 END ASC,
                      v.placeIdCheckedAt ASC,
                      v.id ASC
            """)
    List<Venue> findNeedingPlaceId(@Param("retryBefore") OffsetDateTime retryBefore,
            Pageable pageable);

    /**
     * 解決待ちの残り件数。進み具合をログに出すために使う。
     *
     * <p><b>再試行の間隔は見ない。</b> ここが表すのは「まだ place_id が付いていない会場」で、
     * 今日叩ける件数ではない。1 日で減らなくても、7 日ごとに再試行が続いている
     * （ADR-0022「暴走と無駄叩きを防ぐ」）。
     */
    long countByPlaceIdIsNullAndManuallyEditedFalseAndAreaOnlyFalse();

    /**
     * 未解決の会場だけの一覧（docs/api.md「会場の一覧と編集」の {@code unresolved=true}）。
     *
     * <p><b>manually_edited でも返す。</b> 管理者が place_id を消した行はまさに
     * 「未解決の会場」であり、一覧から消えると直したい行を見失う。
     */
    Page<Venue> findByPlaceIdIsNull(Pageable pageable);
}
