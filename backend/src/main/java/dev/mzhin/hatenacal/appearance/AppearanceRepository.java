package dev.mzhin.hatenacal.appearance;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.time.LocalTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppearanceRepository extends JpaRepository<Appearance, Long> {

    /**
     * 期間内の出演情報を、カレンダーに並べる順で返す。
     *
     * <p>並び順は日付昇順 → 出演開始時刻昇順で、<b>時刻が未設定のものは同じ日付の末尾</b>
     * （FR-03、docs/api.md「期間内の出演情報一覧」）。
     *
     * <p>1 か月分を 1 クエリで取り切る（NFR-01）。先頭列が appearance_date の
     * appearance_unique_event がそのまま範囲検索に使える（docs/data-model.md「インデックス」）。
     */
    @Query("""
            SELECT a FROM Appearance a
             WHERE a.appearanceDate BETWEEN :from AND :to
             ORDER BY a.appearanceDate ASC,
                      CASE WHEN a.performanceStartTime IS NULL THEN 1 ELSE 0 END ASC,
                      a.performanceStartTime ASC,
                      a.id ASC
            """)
    List<Appearance> findForCalendar(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 会場の紐づけが済んでいない行（ADR-0022「既存データの初期投入」）。
     *
     * <p>初期投入と、登録時の紐づけに漏れがあったときの掃除に使う。
     * <b>件数の上限は呼び出し側が {@link Pageable} で渡す。</b>
     * 数百件を 1 トランザクションで抱えない。
     *
     * <p><b>空白だけの会場名を拾わない。</b> 紐づけ側は空白を「会場なし」として
     * {@code venue_id} を {@code null} のままにするため、ここで拾うと
     * <b>永久に同じ行を返し続ける</b>（呼び出し側は残りが無くなるまで繰り返す）。
     * 選ぶ条件と紐づける条件を一致させる。
     */
    @Query("""
            SELECT a FROM Appearance a
             WHERE a.venueId IS NULL
               AND a.venueName IS NOT NULL
               AND TRIM(a.venueName) <> ''
             ORDER BY a.id ASC
            """)
    List<Appearance> findNeedingVenueLink(Pageable pageable);

    /**
     * 紐づけの残り件数。進み具合をログに出すために使う。
     *
     * <p><b>条件を {@link #findNeedingVenueLink} と揃える。</b> ずれると
     * 「残り 3 件」と出続けて 0 件しか拾えない、という噛み合わない状態になる。
     * 一致は {@code VenueLinkIT} が表明している。
     */
    @Query("""
            SELECT count(a) FROM Appearance a
             WHERE a.venueId IS NULL
               AND a.venueName IS NOT NULL
               AND TRIM(a.venueName) <> ''
            """)
    long countNeedingVenueLink();

    /**
     * 会場ごとの出演情報の件数（docs/api.md「会場の一覧と編集」の appearanceCount）。
     *
     * <p>返るのは {@code [venueId, 件数]} の配列。<b>0 件の会場は行として現れない</b>ので、
     * 呼び出し側が既定 0 を補う（{@link AppearanceVenueUsageCounter}）。
     *
     * <p><b>会場ごとに数えない。</b> 一覧の 1 ページ分を 1 クエリで集計する。
     * 行ごとに引くと N+1 になる。
     */
    @Query("""
            SELECT a.venueId, count(a) FROM Appearance a
             WHERE a.venueId IN :ids
             GROUP BY a.venueId
            """)
    List<Object[]> countByVenueIds(@Param("ids") Collection<Long> ids);

    /**
     * 点検一覧（FR-24）の絞り込み。
     *
     * <p><b>並び順はメソッド名で固定しない。</b> 呼び出し側が {@link Pageable} に載せる
     * （{@link AppearanceSort} / docs/api.md「出演情報の一覧と個別取得（点検用）」）。
     * 絞り込み無しは継承した {@code findAll(Pageable)} を使う。
     */
    Page<Appearance> findBySourceType(SourceType sourceType, Pageable pageable);

    /**
     * 一意キーでの照合（ADR-0012）。開始時刻ありの場合。
     *
     * <p>アプリの照合ロジックと DB の UNIQUE 制約が同じキーで判定するようにする。
     *
     * <p><b>NULL 有無で 2 本に分けている。</b> 1 本にまとめて
     * {@code (col = :start OR (col IS NULL AND :start IS NULL))} と書くと、
     * PostgreSQL が裸のパラメータの型を決められず
     * 「could not determine data type of parameter」で落ちる。
     * JPQL に IS NOT DISTINCT FROM がないため、呼び出し側で分岐する。
     */
    Optional<Appearance> findByAppearanceDateAndEventKeyAndPerformanceStartTime(
            LocalDate appearanceDate, String eventKey, LocalTime performanceStartTime);

    /** 一意キーでの照合。開始時刻なしの場合（NULLS NOT DISTINCT に対応）。 */
    Optional<Appearance> findByAppearanceDateAndEventKeyAndPerformanceStartTimeIsNull(
            LocalDate appearanceDate, String eventKey);

    /**
     * その日・そのイベントの行が 1 つでもあるか（開始時刻は問わない）。
     *
     * <p>時刻なしの取り込みが重複行を作らないための判定に使う
     * （docs/data-model.md「追加告知による空欄補完」）。一意キーは開始時刻を含むため、
     * 時刻ありの行があっても時刻なしの行は制約に触れずに作れてしまう。
     */
    boolean existsByAppearanceDateAndEventKey(LocalDate appearanceDate, String eventKey);

}
