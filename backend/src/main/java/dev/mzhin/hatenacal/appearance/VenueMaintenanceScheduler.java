package dev.mzhin.hatenacal.appearance;

import dev.mzhin.hatenacal.venue.PlaceIdResolutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会場の定期メンテナンス（ADR-0022「既存データの初期投入」）。
 *
 * <p><b>手作業を運用の前提にしない。</b> 既存データの初期投入も、登録時の
 * 紐づけに漏れがあったときの掃除も、place_id の解決も、これが自動で進める。
 *
 * <p>2 段で構成する。
 *
 * <pre>
 * 段 1  venue_id が未設定の出演情報に venue を引き当てる      ← DB のみ
 * 段 2  place_id が未解決の会場を上限 N 件だけ解決する         ← Places API
 * </pre>
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
     * 1 トランザクションで紐づける件数。
     *
     * <p><b>1 日あたりの上限ではない。</b> 残りが無くなるまで繰り返すので、
     * 初期投入は最初の実行で片付く。数百件を 1 トランザクションで抱えないための区切り。
     */
    private static final int LINK_BATCH = 200;

    /**
     * 繰り返しの上限。
     *
     * <p>紐づけが進まない行が万一残っても、無限に回さないための歯止め。
     * {@code LINK_BATCH} と掛けて 20,000 件で、当面の規模を大きく上回る。
     */
    private static final int MAX_ROUNDS = 100;

    /**
     * 1 回の実行で place_id を試す上限（ADR-0022「暴走と無駄叩きを防ぐ」）。
     *
     * <p><b>段 1 と違い、残りが尽きるまで繰り返さない。</b> 外部 API を叩くので、
     * 異常時に延々と叩かないための歯止めが要る。
     *
     * <p>本番の会場は 56 行なので初回でほぼ片付き、以後に増えるのは月に数件。
     * 上限に当たるのは初期投入と障害のときだけで、どちらも翌日に続きから進む。
     */
    private static final int RESOLVE_BATCH = 60;

    private final AppearanceService appearances;
    private final PlaceIdResolutionService placeIds;

    public VenueMaintenanceScheduler(AppearanceService appearances,
            PlaceIdResolutionService placeIds) {
        this.appearances = appearances;
        this.placeIds = placeIds;
    }

    /**
     * {@code fixedDelay} を使う。前回の完了からの間隔なので、実行が長引いても重ならない。
     *
     * <p><b>起動の少しあとに 1 回走らせる。</b> デプロイ直後に初期投入が動き、
     * 翌日まで待たされない。
     *
     * <p><b>段 1 の失敗が段 2 を止めない。逆も同じ</b>（ADR-0022「満たすべき性質」）。
     * DB の中だけで完結する処理と、外部 API の可用性に左右される処理を、
     * 片方の事情でもう片方が動かなくなる形に結び付けない。
     */
    @Scheduled(fixedDelayString = "${venue.maintenance.interval:P1D}",
            initialDelayString = "${venue.maintenance.initial-delay:PT5M}")
    public void run() {
        linkVenues();
        resolvePlaceIds();
    }

    /** 段 1。出演情報に会場を引き当てる。 */
    private void linkVenues() {
        try {
            log.info("会場の紐づけを開始: 残り {} 件", appearances.countMissingVenues());
            int total = 0;
            for (int round = 0; round < MAX_ROUNDS; round++) {
                int linked = appearances.linkMissingVenues(LINK_BATCH);
                total += linked;
                // 上限に届かなかったなら、拾うものが尽きている
                if (linked < LINK_BATCH) {
                    log.info("会場の紐づけが完了: {} 件", total);
                    return;
                }
            }
            log.warn("会場の紐づけが上限 {} 回で打ち切られた: {} 件処理。残り {} 件",
                    MAX_ROUNDS, total, appearances.countMissingVenues());
        } catch (RuntimeException e) {
            // 次回が同じ行を拾い直す。ここで握るのは段 2 を止めないため
            log.warn("会場の紐づけに失敗した", e);
        }
    }

    /** 段 2。会場の place_id を解決する。 */
    private void resolvePlaceIds() {
        try {
            placeIds.resolveMissing(RESOLVE_BATCH);
        } catch (RuntimeException e) {
            // 未解決のままでも地図リンクは名前検索で機能する（ADR-0022）
            log.warn("place_id の解決に失敗した", e);
        }
    }
}
