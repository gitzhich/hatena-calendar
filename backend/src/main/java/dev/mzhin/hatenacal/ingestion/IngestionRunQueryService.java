package dev.mzhin.hatenacal.ingestion;

import dev.mzhin.hatenacal.appearance.CalendarRange;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 取り込み履歴の読み取り（docs/api.md 第 5.7 節、FR-42 / NFR-04 / NFR-09）。
 *
 * <p>管理者しか見られない情報を扱う。認可は {@code /api/admin/**} に対する
 * {@link dev.mzhin.hatenacal.config.SecurityConfig} のデフォルト拒否で担保しており、
 * このクラスは<b>更新系のメソッドを持たない</b>（読み取りのみ）。
 *
 * <p><b>この情報を公開 API に載せない</b>（NFR-03 / docs/api.md 第 4.2 節）。
 * 失敗理由・取得リソース数・実行中かどうかは運用の情報であり、
 * 閲覧者向けには最終成功日時だけを {@link IngestionStatusService} が返す。
 */
@Service
public class IngestionRunQueryService {

    private final IngestionRunRepository runs;
    private final Clock clock;

    public IngestionRunQueryService(IngestionRunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public IngestionRunListResponse list(Pageable pageable) {
        Page<IngestionRun> page = runs.findAllByOrderByStartedAtDesc(pageable);
        // 打ち切りの判定は常に直近の実行を見る。表示中のページに引きずられない
        List<IngestionRun> recent = runs.findByOrderByStartedAtDesc(IngestionHaltRule.window());
        long resources = runs.sumFetchedResourceCountSince(
                currentMonthStart(OffsetDateTime.now(clock)));

        return new IngestionRunListResponse(
                page.getContent().stream().map(IngestionRunDto::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                resources,
                IngestionHaltRule.consecutiveFailures(recent),
                IngestionHaltRule.halted(recent));
    }

    /**
     * 当月の起点（NFR-04）。
     *
     * <p><b>暦月の境界は JST で切る</b>（NFR-05）。管理者が見る「今月」は JST の暦月であり、
     * UTC で切ると月初 9 時間分が前月に混じる。
     *
     * <p>これは<b>概算のための区切りであって請求期間ではない</b>。X API の請求サイクルは
     * クレジットの購入日を起点に切られ、暦月と一致しない
     * （docs/runbook-x-api-setup.md 第 3.3 節）。正確な請求額は X の管理画面で確認する。
     */
    static OffsetDateTime currentMonthStart(OffsetDateTime now) {
        return now.atZoneSameInstant(CalendarRange.JST)
                .toLocalDate()
                .withDayOfMonth(1)
                .atStartOfDay(CalendarRange.JST)
                .toOffsetDateTime();
    }
}
