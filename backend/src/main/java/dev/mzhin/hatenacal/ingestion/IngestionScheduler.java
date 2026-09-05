package dev.mzhin.hatenacal.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 取り込みの定期実行（docs/x-integration.md「運用と監視」）。
 *
 * <p><b>ポーリング間隔を決めているのは X API ではない。</b>
 * 新規投稿がなければ返却リソースは 0 件で課金も 0 だが、実行のたびに
 * Neon のコンピュートが最低 5 分起動するため、間隔を詰めると無料枠の
 * CU-hours を圧迫する（docs/architecture.md「運用コストの試算」）。
 *
 * <p>{@code X_INGESTION_ENABLED=false} でこのビーン自体が作られなくなる。
 * トークンを消す以外の止め方（docs/security.md「未決定事項」）。
 * <b>反映にはデプロイが要る。</b>
 */
@Component
@ConditionalOnProperty(name = "ingestion.enabled", havingValue = "true",
        matchIfMissing = true)
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final IngestionService service;

    public IngestionScheduler(IngestionService service) {
        this.service = service;
    }

    /**
     * {@code fixedDelay} を使う。前回の完了からの間隔なので、実行が長引いても
     * 重ならない。多重起動の防止は別途あるが（docs/x-integration.md「多重起動の防止」）、そもそも重ねない。
     *
     * <p>起動直後には走らせない。デプロイのたびに取得が走るのを避け、
     * テストの文脈でも発火しないようにするため。
     */
    @Scheduled(fixedDelayString = "${ingestion.interval:PT30M}",
            initialDelayString = "${ingestion.initial-delay:PT1M}")
    public void run() {
        IngestionService.Result result = service.run();
        if (result != IngestionService.Result.COMPLETED) {
            log.info("定期取り込みの結果: {}", result);
        }
    }
}
