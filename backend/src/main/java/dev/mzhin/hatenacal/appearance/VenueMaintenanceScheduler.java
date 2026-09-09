package dev.mzhin.hatenacal.appearance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会場の定期メンテナンス（ADR-0022「既存データの初期投入」）。
 *
 * <p><b>手作業を運用の前提にしない。</b> 既存データの初期投入も、登録時の
 * 紐づけに漏れがあったときの掃除も、これが自動で進める。
 *
 * <p><b>1 日 1 回で足りる。</b> Neon は最終クエリから 5 分でサスペンドするため、
 * ジョブが DB に触るたびに最低 5 分は起動したままになる
 * （docs/architecture.md「運用コストの試算」）。毎時にすると月 15 CU-hours を
 * 恒久的に食い、閲覧・再検証に残る枠の 2 割強を削る。1 日 1 回なら 0.6 CU-hours。
 *
 * <p><b>取り込みとは独立している。</b> 取り込みが連続失敗で打ち切られていても
 * これは走る（FR-43）。逆に、これが失敗しても取り込みには影響しない。
 */
@Component
@ConditionalOnProperty(name = "venue.maintenance.enabled", havingValue = "true",
        matchIfMissing = true)
public class VenueMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(VenueMaintenanceScheduler.class);

    /**
     * 1 回の実行で紐づける上限。
     *
     * <p>数百件を 1 トランザクションで抱えない。本番の規模（出演情報は数百件）なら
     * 初回の 1〜2 回で終わる。
     */
    private static final int LINK_BATCH = 200;

    private final AppearanceService appearances;

    public VenueMaintenanceScheduler(AppearanceService appearances) {
        this.appearances = appearances;
    }

    /**
     * {@code fixedDelay} を使う。前回の完了からの間隔なので、実行が長引いても重ならない。
     *
     * <p><b>起動の少しあとに 1 回走らせる。</b> デプロイ直後に初期投入が動き、
     * 翌日まで待たされない。
     */
    @Scheduled(fixedDelayString = "${venue.maintenance.interval:P1D}",
            initialDelayString = "${venue.maintenance.initial-delay:PT5M}")
    public void run() {
        try {
            int linked = appearances.linkMissingVenues(LINK_BATCH);
            if (linked > 0) {
                log.info("会場を紐づけた: {} 件", linked);
            }
        } catch (RuntimeException e) {
            // ここで握るのは、次の段（place_id の解決）を止めないため。
            // 失敗しても次回が同じ行を拾い直す
            log.warn("会場の紐づけに失敗した", e);
        }
    }
}
