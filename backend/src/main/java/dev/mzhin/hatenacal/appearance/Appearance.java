package dev.mzhin.hatenacal.appearance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 出演情報。docs/data-model.md 第 4.3 節。
 *
 * <p>appearance_date と performance_start_time / merch_start_time は
 * <b>JST のローカル値</b>であり、UTC へ変換しない（同 第 6 章 / ADR-0005）。
 * LocalDate / LocalTime を使うのはそのため。OffsetDateTime を使うと
 * 月境界で日付がずれる。
 *
 * <p>createdAt / updatedAt は TIMESTAMPTZ で UTC 保存し、表示時に JST へ変換する。
 */
@Entity
@Table(name = "appearance")
public class Appearance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appearance_date", nullable = false)
    private LocalDate appearanceDate;

    /** 表示用。告知の原文をそのまま持つ（ADR-0006）。 */
    @Column(name = "event_name", nullable = false)
    private String eventName;

    /** 照合用の正規化済みイベント名。画面には出さない（ADR-0006）。 */
    @Column(name = "event_key", nullable = false)
    private String eventKey;

    @Column(name = "venue_name")
    private String venueName;

    @Column(name = "performance_start_time")
    private LocalTime performanceStartTime;

    @Column(name = "performance_end_time")
    private LocalTime performanceEndTime;

    @Column(name = "merch_start_time")
    private LocalTime merchStartTime;

    @Column(name = "merch_end_time")
    private LocalTime merchEndTime;

    @Column(name = "ticket_url")
    private String ticketUrl;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @Column(name = "ingested_post_id")
    private Long ingestedPostId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Appearance() {
        // JPA 用
    }

    /**
     * 新規作成。
     *
     * <p>event_key を外から渡す形にしているのは、生成を AppearanceService の
     * 単一メソッドに閉じ込めるため（ADR-0006）。パッケージ内からしか呼べない。
     */
    static Appearance create(String eventKey, SourceType sourceType,
            LocalDate appearanceDate, String eventName, String venueName,
            LocalTime performanceStartTime, LocalTime performanceEndTime,
            LocalTime merchStartTime, LocalTime merchEndTime,
            String ticketUrl, String sourceUrl, Long ingestedPostId) {
        Appearance a = new Appearance();
        a.eventKey = eventKey;
        a.sourceType = sourceType;
        a.appearanceDate = appearanceDate;
        a.eventName = eventName;
        a.venueName = venueName;
        a.performanceStartTime = performanceStartTime;
        a.performanceEndTime = performanceEndTime;
        a.merchStartTime = merchStartTime;
        a.merchEndTime = merchEndTime;
        a.ticketUrl = ticketUrl;
        a.sourceUrl = sourceUrl;
        a.ingestedPostId = ingestedPostId;
        return a;
    }

    /**
     * 追加告知による空欄補完（docs/data-model.md 第 7.1 節、FR-41）。
     *
     * <p><b>値が入っている列は上書きしない。</b>これにより、管理者が手で直した
     * 内容が後続の取り込みで巻き戻らない（FR-22）。日程変更や中止の反映は
     * 自動では行わず、管理者が手で対応する。
     *
     * <p>補完が起きたときは {@code sourceUrl} と {@code ingestedPostId} を
     * <b>その告知のものへ更新する</b>。出演時刻を載せた告知が出典として
     * 示されるべきで、時刻の書かれていない最初の告知を指し続けるのは
     * FR-06 の趣旨に反する。
     *
     * <p>{@code sourceType} は変えない。手動登録された行は、後続の取り込みで
     * 空欄が埋まっても MANUAL のままにする。作ったのは管理者だからである。
     *
     * @return 1 つでも埋めたか。何も埋まらなければ出典も更新しない
     */
    boolean fillBlanks(String venueName,
            LocalTime performanceStartTime, LocalTime performanceEndTime,
            LocalTime merchStartTime, LocalTime merchEndTime,
            String ticketUrl, String sourceUrl, Long ingestedPostId) {
        boolean filled = false;
        if (this.venueName == null && venueName != null) {
            this.venueName = venueName;
            filled = true;
        }
        if (this.performanceStartTime == null && performanceStartTime != null) {
            this.performanceStartTime = performanceStartTime;
            filled = true;
        }
        if (this.performanceEndTime == null && performanceEndTime != null) {
            this.performanceEndTime = performanceEndTime;
            filled = true;
        }
        if (this.merchStartTime == null && merchStartTime != null) {
            this.merchStartTime = merchStartTime;
            filled = true;
        }
        if (this.merchEndTime == null && merchEndTime != null) {
            this.merchEndTime = merchEndTime;
            filled = true;
        }
        if (this.ticketUrl == null && ticketUrl != null) {
            this.ticketUrl = ticketUrl;
            filled = true;
        }
        if (filled) {
            this.sourceUrl = sourceUrl;
            this.ingestedPostId = ingestedPostId;
        }
        return filled;
    }

    /** 全項目を差し替える。部分更新にしない理由は docs/api.md 第 5.3 節。 */
    void replace(String eventKey, LocalDate appearanceDate, String eventName,
            String venueName, LocalTime performanceStartTime, LocalTime performanceEndTime,
            LocalTime merchStartTime, LocalTime merchEndTime,
            String ticketUrl, String sourceUrl) {
        this.eventKey = eventKey;
        this.appearanceDate = appearanceDate;
        this.eventName = eventName;
        this.venueName = venueName;
        this.performanceStartTime = performanceStartTime;
        this.performanceEndTime = performanceEndTime;
        this.merchStartTime = merchStartTime;
        this.merchEndTime = merchEndTime;
        this.ticketUrl = ticketUrl;
        this.sourceUrl = sourceUrl;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getAppearanceDate() {
        return appearanceDate;
    }

    public String getEventName() {
        return eventName;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getVenueName() {
        return venueName;
    }

    public LocalTime getPerformanceStartTime() {
        return performanceStartTime;
    }

    public LocalTime getPerformanceEndTime() {
        return performanceEndTime;
    }

    public LocalTime getMerchStartTime() {
        return merchStartTime;
    }

    public LocalTime getMerchEndTime() {
        return merchEndTime;
    }

    public String getTicketUrl() {
        return ticketUrl;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public Long getIngestedPostId() {
        return ingestedPostId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
