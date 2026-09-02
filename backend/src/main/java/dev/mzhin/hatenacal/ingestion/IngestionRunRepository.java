package dev.mzhin.hatenacal.ingestion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, Long> {

    List<IngestionRun> findByStatusOrderByStartedAtAsc(IngestionRunStatus status);

    Optional<IngestionRun> findFirstByOrderByStartedAtDesc();

    /** 直近の実行を新しい順に。連続失敗の判定に使う（FR-43）。 */
    List<IngestionRun> findTop10ByOrderByStartedAtDesc();

    /**
     * 期間内の取得リソース数の合計（NFR-04）。
     *
     * <p>請求サイクルは購入日起点で切られ暦月と一致しないため、期間は呼び出し側が渡す
     * （docs/runbook-x-api-setup.md 第 3.3 節）。
     */
    @Query("""
            SELECT coalesce(sum(r.fetchedResourceCount), 0)
              FROM IngestionRun r
             WHERE r.startedAt >= :from
            """)
    long sumFetchedResourceCountSince(@Param("from") OffsetDateTime from);
}
