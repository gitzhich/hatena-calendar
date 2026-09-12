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

    /**
     * 一覧の並び順（{@link Region} の宣言順 → 表記 → id）。
     *
     * <p><b>ELSE を先頭に落とす。</b> {@link Region} に地方を足して、ここを直し忘れると
     * その地方だけ並びが崩れる。SQL としては正しいので落ちない。先頭に出しておけば
     * 並び順のテスト（{@code AdminVenueApiIT}）が必ず落ちる。末尾（{@code UNKNOWN} の
     * 後ろ）に落とすと、{@code UNKNOWN} を書き漏らした場合だけ素通りしてしまう。
     */
    String REGION_ORDER = """
             ORDER BY CASE
                        WHEN v.region = dev.mzhin.hatenacal.venue.Region.HOKKAIDO THEN 0
                        WHEN v.region = dev.mzhin.hatenacal.venue.Region.TOHOKU THEN 1
                        WHEN v.region = dev.mzhin.hatenacal.venue.Region.KANTO THEN 2
                        WHEN v.region = dev.mzhin.hatenacal.venue.Region.CHUBU THEN 3
                        WHEN v.region = dev.mzhin.hatenacal.venue.Region.KINKI THEN 4
                        WHEN v.region = dev.mzhin.hatenacal.venue.Region.CHUGOKU THEN 5
                        WHEN v.region = dev.mzhin.hatenacal.venue.Region.SHIKOKU THEN 6
                        WHEN v.region = dev.mzhin.hatenacal.venue.Region.KYUSHU THEN 7
                        WHEN v.region = dev.mzhin.hatenacal.venue.Region.OVERSEAS THEN 8
                        WHEN v.region = dev.mzhin.hatenacal.venue.Region.UNKNOWN THEN 9
                        ELSE -1
                      END ASC,
                      v.displayName ASC,
                      v.id ASC
            """;

    String REGION_ORDER_QUERY = "SELECT v FROM Venue v" + REGION_ORDER;

    String UNRESOLVED_ORDER_QUERY =
            "SELECT v FROM Venue v WHERE v.placeId IS NULL" + REGION_ORDER;

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
     * 管理画面の一覧（docs/api.md「会場の一覧と編集」）。
     *
     * <p><b>地方でまとまる順に返す。</b> {@code region} は文字列で保存するため、
     * 素直に並べるとアルファベット順（CHUBU, CHUGOKU, HOKKAIDO…）になり、
     * 固まりはするが並びが恣意的になる。
     *
     * <p><b>最後に id で決着させる。</b> 同じ表記の行は作れないが、並び順を
     * 一意に決めておかないとページの境界で取りこぼしと重複が出る
     * （docs/api.md「出演情報の一覧と個別取得（点検用）」と同じ理由）。
     *
     * <p><b>{@link Pageable} に {@code Sort} を載せない。</b> 載せるとここの
     * {@code ORDER BY} の後ろに追記され、意図した順が後段で崩れる。
     */
    @Query(value = REGION_ORDER_QUERY,
            countQuery = "SELECT COUNT(v) FROM Venue v")
    Page<Venue> findAllOrdered(Pageable pageable);

    /**
     * 未解決の会場だけの一覧（docs/api.md「会場の一覧と編集」の {@code unresolved=true}）。
     *
     * <p><b>manually_edited でも返す。</b> 管理者が place_id を消した行はまさに
     * 「未解決の会場」であり、一覧から消えると直したい行を見失う。
     *
     * <p>並び順は {@link #findAllOrdered(Pageable)} と同じ。
     */
    @Query(value = UNRESOLVED_ORDER_QUERY,
            countQuery = "SELECT COUNT(v) FROM Venue v WHERE v.placeId IS NULL")
    Page<Venue> findUnresolvedOrdered(Pageable pageable);
}
