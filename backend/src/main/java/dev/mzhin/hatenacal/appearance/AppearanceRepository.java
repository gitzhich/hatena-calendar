package dev.mzhin.hatenacal.appearance;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppearanceRepository extends JpaRepository<Appearance, Long> {

    /**
     * 期間内の出演情報を、カレンダーに並べる順で返す。
     *
     * <p>並び順は日付昇順 → 出演開始時刻昇順で、<b>時刻が未設定のものは同じ日付の末尾</b>
     * （FR-03、docs/api.md 第 4.1 節）。
     *
     * <p>1 か月分を 1 クエリで取り切る（NFR-01）。先頭列が appearance_date の
     * appearance_unique_event がそのまま範囲検索に使える（docs/data-model.md 第 5 章）。
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
}
