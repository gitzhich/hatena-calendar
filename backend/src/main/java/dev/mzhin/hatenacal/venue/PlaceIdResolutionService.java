package dev.mzhin.hatenacal.venue;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * place_id の解決（ADR-0022「既存データの初期投入」の段 2）。
 *
 * <p><b>{@link VenueService} と分けている。</b> あちらは DB の中だけで完結し、
 * 取り込みのトランザクションから呼んで安全であることを契約にしている。
 * 外部 API を呼ぶ処理を同じクラスに置くと、その契約が読み取れなくなる。
 *
 * <p><b>HTTP をトランザクションの外で呼ぶ。</b> 対象の取得と 1 件ごとの記録を
 * それぞれ別のトランザクションにし、Google の応答を待つ間 DB の接続を握らない。
 * Neon は最終クエリから 5 分でサスペンドするため、接続を抱えた分だけ
 * CU-hours を食う（docs/architecture.md「運用コストの試算」）。
 */
@Service
public class PlaceIdResolutionService {

    private static final Logger log = LoggerFactory.getLogger(PlaceIdResolutionService.class);

    /**
     * 再試行の間隔（ADR-0022「暴走と無駄叩きを防ぐ」）。
     *
     * <p>見つからなかった会場を毎日叩かない。Google に無いものは明日も無い。
     * 表記を直せば見つかるようになるので、諦めきりもしない。
     */
    static final Duration RETRY_INTERVAL = Duration.ofDays(7);

    private final VenueRepository repository;
    private final PlacesClient places;
    private final PlacesProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PlaceIdResolutionService(VenueRepository repository, PlacesClient places,
            PlacesProperties properties, TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        this.places = places;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * 未解決の会場を上限件数だけ解決する。
     *
     * <p><b>Google に到達できなくなったらその場で打ち切る。</b> 障害中に上限まで
     * 叩き続けても何も得られないうえ、試行として記録すれば再試行が 7 日先へ飛ぶ。
     * 打ち切っても翌日また走る。
     *
     * @param limit 1 回の実行で試す上限
     * @return 解決できた件数
     */
    public int resolveMissing(int limit) {
        if (!properties.configured()) {
            // キーが無くてもアプリは動く。地図リンクは名前検索に落ちるだけ（ADR-0022）
            log.info("GOOGLE_MAPS_API_KEY が未設定のため place_id の解決を行わない");
            return 0;
        }
        List<Venue> targets = repository.findNeedingPlaceId(retryBefore(),
                PageRequest.of(0, limit));
        log.info("place_id の解決を開始: 今回 {} 件 / 未解決は全体で {} 件",
                targets.size(), repository.countByPlaceIdIsNullAndManuallyEditedFalseAndAreaOnlyFalse());

        int resolved = 0;
        int attempted = 0;
        for (Venue target : targets) {
            Optional<String> found;
            try {
                found = places.findPlaceId(target.getDisplayName());
            } catch (PlacesException e) {
                log.warn("Places API に到達できないため打ち切る（{} 件試行、{} 件解決）: {}",
                        attempted, resolved, e.getMessage());
                return resolved;
            }
            attempted++;
            record(target.getId(), found.orElse(null));
            if (found.isPresent()) {
                resolved++;
            }
        }
        log.info("place_id の解決が完了: {} 件試行、{} 件解決", attempted, resolved);
        return resolved;
    }

    /**
     * 1 件だけ解決する（docs/api.md「会場の一覧と編集」の {@code resolve-place-id}）。
     *
     * <p>再試行の間隔を待たずに今すぐ試すための入口。<b>間隔も未解決かどうかも見ない</b>——
     * 管理者が明示的に叩いているので、判断はその人に委ねる。
     *
     * @return 解決できたか
     * @throws PlacesException Google に到達できない。試行として記録しない
     */
    public boolean resolveOne(Venue target) {
        Optional<String> found = places.findPlaceId(target.getDisplayName());
        record(target.getId(), found.orElse(null));
        return found.isPresent();
    }

    private OffsetDateTime retryBefore() {
        return OffsetDateTime.now(clock).minus(RETRY_INTERVAL);
    }

    /**
     * 試行の結果を 1 件ずつ記録する。
     *
     * <p><b>読み直してから書く。</b> 対象を選んでから記録するまでに HTTP を挟むため、
     * その間に管理者が同じ行を直しているかもしれない。人が入れた値を機械が消さない
     * （ADR-0022）。
     */
    private void record(Long venueId, String placeId) {
        tx.executeWithoutResult(status -> repository.findById(venueId).ifPresent(venue -> {
            if (!venue.isManuallyEdited()) {
                venue.recordPlaceIdAttempt(placeId, OffsetDateTime.now(clock));
            }
        }));
    }
}
