package dev.mzhin.hatenacal.ingestion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestedPostRepository extends JpaRepository<IngestedPost, Long> {

    /** 未処理投稿の一覧（FR-25）。新しい順（docs/api.md 第 5.5 節）。 */
    Page<IngestedPost> findByStatusOrderByPostedAtDesc(IngestedPostStatus status,
            Pageable pageable);
}
