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
     * 最後に取り込みが成功した日時（FR-08 / docs/data-model.md 第 4.4 節）。
     *
     * <p>引数で状態を受け取らない。「成功した日時」であることが FR-08 の意味そのもので、
     * 呼び出し側が誤って FAILED を渡せる余地を残さない。
     *
     * <p><b>ソートの先頭ではなく max を使う。</b> finished_at の降順で先頭を取る形だと、
     * PostgreSQL の既定が NULLS FIRST のため、finished_at が NULL の行が先に来る余地が残る。
     * max は NULL を無視するので、その余地がない。
     */
    @Query("""
            SELECT max(r.finishedAt)
              FROM IngestionRun r
             WHERE r.status = dev.mzhin.hatenacal.ingestion.IngestionRunStatus.SUCCESS
            """)
    Optional<OffsetDateTime> findLastSuccessfulFinishedAt();

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
