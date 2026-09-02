package dev.mzhin.hatenacal.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 取り込み実行の記録（docs/data-model.md 第 4.3 節、FR-42）。
 *
 * <p>課金額を後から追跡できるようにするための台帳でもある。
 * {@code fetchedResourceCount} の当月合計が想定（月 300 前後）から桁違いに増えていれば
 * 第三者利用を疑う（docs/security.md T-01）。
 */
@Entity
@Table(name = "ingestion_run")
public class IngestionRun {

    /** error_summary の DB 側の上限（docs/data-model.md 第 4.3 節）。 */
    static final int MAX_ERROR_SUMMARY = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IngestionRunStatus status;

    @Column(name = "fetched_resource_count", nullable = false)
    private int fetchedResourceCount;

    @Column(name = "new_appearance_count", nullable = false)
    private int newAppearanceCount;

    @Column(name = "error_summary")
    private String errorSummary;

    protected IngestionRun() {
        // JPA 用
    }

    /** 実行を開始する。この行が残っていることが多重起動の判定材料になる（第 10.1 節）。 */
    static IngestionRun start(OffsetDateTime now) {
        IngestionRun run = new IngestionRun();
        run.startedAt = now;
        run.status = IngestionRunStatus.RUNNING;
        return run;
    }

    void succeed(OffsetDateTime now, int fetchedResourceCount, int newAppearanceCount) {
        this.status = IngestionRunStatus.SUCCESS;
        this.finishedAt = now;
        this.fetchedResourceCount = fetchedResourceCount;
        this.newAppearanceCount = newAppearanceCount;
    }

    /**
     * 失敗として閉じる。
     *
     * <p>{@code summary} に<b>スタックトレースとトークンを含めない</b>（NFR-03）。
     * 呼び出し側が組み立てた要約だけを受け取り、ここで長さを DB の上限に合わせる。
     */
    void fail(OffsetDateTime now, String summary, int fetchedResourceCount) {
        this.status = IngestionRunStatus.FAILED;
        this.finishedAt = now;
        this.fetchedResourceCount = fetchedResourceCount;
        this.errorSummary = truncate(summary);
    }

    private static String truncate(String summary) {
        if (summary == null) {
            return null;
        }
        return summary.length() <= MAX_ERROR_SUMMARY
                ? summary
                : summary.substring(0, MAX_ERROR_SUMMARY - 1) + "…";
    }

    public Long getId() {
        return id;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public IngestionRunStatus getStatus() {
        return status;
    }

    public int getFetchedResourceCount() {
        return fetchedResourceCount;
    }

    public int getNewAppearanceCount() {
        return newAppearanceCount;
    }

    public String getErrorSummary() {
        return errorSummary;
    }
}
