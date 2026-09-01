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
