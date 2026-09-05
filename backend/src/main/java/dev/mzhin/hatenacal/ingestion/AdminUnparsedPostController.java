package dev.mzhin.hatenacal.ingestion;

import dev.mzhin.hatenacal.common.NotFoundException;
import dev.mzhin.hatenacal.common.PageResponse;
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
    private final SourceAccountRepository sourceAccounts;

    public AdminUnparsedPostController(IngestedPostRepository repository,
            SourceAccountRepository sourceAccounts) {
        this.repository = repository;
        this.sourceAccounts = sourceAccounts;
    }

    /**
     * 投稿 URL に使うハンドル。<b>正本は {@code source_account} の行</b>
     * （docs/data-model.md「source_account — 情報源アカウント」 / docs/architecture.md「設定と環境変数」）。
     *
     * <p>以前は {@code X_SOURCE_USERNAME} を既定値つきで読んでいた。
     * 設定漏れのとき、<b>実在しうる無関係のアカウント名を指す URL</b> を
     * 管理画面に出してしまう。環境変数は「どのアカウントを取り込むか」の
     * 指定であって、取り込み済みデータの表示に使う値ではない。
     *
     * <p>行が無ければ未処理投稿も存在しない（外部キーで紐づく）ため、
     * 既定値は要らない。
     */
    private String sourceUsername() {
        return sourceAccounts.findAll().stream()
                .findFirst()
                .map(SourceAccount::getUsername)
                .orElse(null);
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
                p -> UnparsedPostDto.from(p, sourceUsername()));
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
