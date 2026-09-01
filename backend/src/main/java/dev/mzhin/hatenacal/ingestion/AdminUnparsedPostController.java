package dev.mzhin.hatenacal.ingestion;

import dev.mzhin.hatenacal.common.NotFoundException;
import dev.mzhin.hatenacal.common.PageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 未処理投稿（FR-25 / docs/api.md 第 5.5・5.6 節）。 */
@RestController
@RequestMapping("/api/admin/unparsed-posts")
public class AdminUnparsedPostController {

    private static final int MAX_PAGE_SIZE = 100;

    private final IngestedPostRepository repository;
    private final String sourceUsername;

    public AdminUnparsedPostController(IngestedPostRepository repository,
            @Value("${X_SOURCE_USERNAME:xinxin}") String sourceUsername) {
        this.repository = repository;
        this.sourceUsername = sourceUsername;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<UnparsedPostDto> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int capped = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(
                repository.findByStatusOrderByPostedAtDesc(IngestedPostStatus.UNPARSED,
                        PageRequest.of(Math.max(page, 0), capped)),
                p -> UnparsedPostDto.from(p, sourceUsername));
    }

    /** 出演告知ではない投稿を一覧から外す（FR-25）。 */
    @PostMapping("/{id}/exclude")
    @Transactional
    public ResponseEntity<Void> exclude(@PathVariable Long id) {
        IngestedPost post = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("投稿が見つかりません"));
        post.markExcluded();
        return ResponseEntity.noContent().build();
    }
}
