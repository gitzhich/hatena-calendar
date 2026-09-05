package dev.mzhin.hatenacal.ingestion;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * データの鮮度を返す（FR-08）。
 *
 * <p>公開 API は読み取り専用で誰でも見られるデータのため、この層での認可検証は行わない
 * （{@link dev.mzhin.hatenacal.appearance.AppearanceService} と同じ扱い）。
 */
@Service
public class IngestionStatusService {

    /**
     * FR-08 の「24 時間」。
     *
     * <p><b>閲覧者に見せる鮮度の基準はここ 1 か所に集約する</b>（docs/api.md「データの状態」）。
     * フロントで再判定すると、サーバとフロントで基準がずれても誰も気づけない。
     */
    static final Duration STALE_THRESHOLD = Duration.ofHours(24);

    private final IngestionRunRepository runs;
    private final Clock clock;

    public IngestionStatusService(IngestionRunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublicStatusDto current() {
        OffsetDateTime lastSuccess = runs.findLastSuccessfulFinishedAt().orElse(null);
        return new PublicStatusDto(lastSuccess, isStale(lastSuccess, OffsetDateTime.now(clock)));
    }

    /**
     * 最終成功から 24 時間以上経過しているか。
     *
     * <p><b>境界は 24 時間ちょうどを含む</b>（FR-08 は「24 時間以上」）。
     *
     * <p><b>一度も成功していなければ false を返す。</b> FR-08 の警告は最終更新日時に
     * 併記するものであり、併記する日時が無い場面で警告だけを出しても閲覧者は行動を
     * 決められない。加えて、手動登録だけで運用している間（取り込みを止めているのが
     * 正常な状態）ずっと警告が出続けることになり、警告そのものが意味を失う。
     * 取り込みが動いていないことの検知は運用側の責務（NFR-04 / FR-24）。
     */
    static boolean isStale(OffsetDateTime lastSuccess, OffsetDateTime now) {
        if (lastSuccess == null) {
            return false;
        }
        return Duration.between(lastSuccess, now).compareTo(STALE_THRESHOLD) >= 0;
    }
}
