package dev.mzhin.hatenacal.ingestion;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceAccountRepository extends JpaRepository<SourceAccount, Long> {

    Optional<SourceAccount> findByUsername(String username);

    /**
     * 取得位置を進める。<b>前進するときだけ更新する。</b>
     *
     * <p>条件を SQL 側に置いているのは、読み出してから比較する実装だと
     * 並行実行で後退しうるため。後退は再課金に直結する
     * （docs/x-integration.md「取得位置を後退させない」、FR-43）。
     *
     * @return 更新された行数。後退させようとした場合は 0
     */
    @Modifying
    @Query("""
            UPDATE SourceAccount a
               SET a.lastFetchedTweetId = :tweetId
             WHERE a.id = :id
               AND (a.lastFetchedTweetId IS NULL OR a.lastFetchedTweetId < :tweetId)
            """)
    int advanceLastFetchedTweetId(@Param("id") Long id, @Param("tweetId") Long tweetId);
}
