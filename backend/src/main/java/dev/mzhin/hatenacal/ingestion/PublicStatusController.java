package dev.mzhin.hatenacal.ingestion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公開 API のデータ状態（docs/api.md 第 4.2 節、FR-08）。
 *
 * <p><b>更新系のメソッドをこのクラスに書かない</b>（NFR-03）。
 * 公開 API は読み取り専用であり、GET 以外を提供しない。
 *
 * <p>取り込みの実行ログのうち<b>最終成功日時だけ</b>を出す。失敗理由・取得件数は
 * 運用の内部情報であり、公開 API に載せない（NFR-03 / docs/security.md）。
 */
@RestController
@RequestMapping("/api/public/status")
public class PublicStatusController {

    private final IngestionStatusService service;

    public PublicStatusController(IngestionStatusService service) {
        this.service = service;
    }

    @GetMapping
    public PublicStatusDto status() {
        return service.current();
    }
}
