package dev.mzhin.hatenacal.ingestion;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 取り込み履歴（docs/api.md「取り込み履歴」）。認証必須。
 *
 * <p>NFR-04 のコスト追跡と NFR-09 の失敗検知に使う。
 *
 * <p><b>更新系のメソッドをこのクラスに書かない。</b> 打ち切りからの復帰は
 * 「原因を確認してから手で戻す」運用であり（FR-43）、画面のボタン 1 つで
 * 再開できるようにすると、原因が残ったまま再開されて再課金が積み上がる。
 */
@RestController
@RequestMapping("/api/admin/ingestion-runs")
public class AdminIngestionRunController {

    private static final int MAX_PAGE_SIZE = 100;

    private final IngestionRunQueryService service;

    public AdminIngestionRunController(IngestionRunQueryService service) {
        this.service = service;
    }

    @GetMapping
    public IngestionRunListResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int capped = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return service.list(PageRequest.of(Math.max(page, 0), capped));
    }
}
