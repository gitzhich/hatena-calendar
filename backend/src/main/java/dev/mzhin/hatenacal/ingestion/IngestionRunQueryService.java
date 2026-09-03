package dev.mzhin.hatenacal.ingestion;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private final XApiProperties properties;
    private final Clock clock;

    public IngestionRunQueryService(IngestionRunRepository runs,
            XApiProperties properties, Clock clock) {
        this.runs = runs;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public IngestionRunListResponse list(Pageable pageable) {
        Page<IngestionRun> page = runs.findAllByOrderByStartedAtDesc(pageable);
        // 打ち切りの判定は常に直近の実行を見る。表示中のページに引きずられない
        List<IngestionRun> recent = runs.findByOrderByStartedAtDesc(IngestionHaltRule.window());
        /*
         * 集計期間は請求サイクル（NFR-04）。暦月で切ると支出上限のリセット日と
         * ずれ、「想定を超えたら気づける」が成り立たない（BillingCycle）。
         */
        OffsetDateTime cycleStart = BillingCycle.startOf(
                OffsetDateTime.now(clock), properties.billingCycleStartDay());
        long resources = runs.sumFetchedResourceCountSince(cycleStart);

        return new IngestionRunListResponse(
                page.getContent().stream().map(IngestionRunDto::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                resources,
                cycleStart.withOffsetSameInstant(ZoneOffset.UTC),
                IngestionHaltRule.consecutiveFailures(recent),
                IngestionHaltRule.halted(recent));
    }
}
