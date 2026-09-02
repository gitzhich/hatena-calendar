package dev.mzhin.hatenacal.ingestion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestedPostRepository extends JpaRepository<IngestedPost, Long> {

    /** 取り込み済みか。再処理で出演情報を二重に作らないための門（FR-41）。 */
    boolean existsByTweetId(Long tweetId);

    /** 未処理投稿の一覧（FR-25）。新しい順（docs/api.md 第 5.5 節）。 */
    Page<IngestedPost> findByStatusOrderByPostedAtDesc(IngestedPostStatus status,
            Pageable pageable);
}
