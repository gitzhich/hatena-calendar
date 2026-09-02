package dev.mzhin.hatenacal.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 情報源アカウント（docs/data-model.md 第 4.1 節）。運用上は 1 行だけ持つ。
 *
 * <p>{@code xUserId} は初回に一度だけ解決して永続化する。username からの解決は
 * 1 回 $0.010 かかるため、以降は<b>二度と呼ばない</b>（FR-40）。
 */
@Entity
@Table(name = "source_account")
public class SourceAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** X のハンドル。{@code @} を含めない。 */
    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "x_user_id", nullable = false, unique = true)
    private Long xUserId;

    /**
     * 取得済みの最大 tweet_id。次回の {@code since_id} に渡す。
     *
     * <p>{@code null} は未取得を意味し、初回バックフィルの対象になる（第 8 章）。
     * <b>後退させてはいけない</b>。後退すると同じ投稿を翌日以降に取り直し、
     * 24 時間の重複排除が効かず再課金になる（docs/x-integration.md 第 2.2 節）。
     */
    @Column(name = "last_fetched_tweet_id")
    private Long lastFetchedTweetId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SourceAccount() {
        // JPA 用
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public Long getXUserId() {
        return xUserId;
    }

    public Long getLastFetchedTweetId() {
        return lastFetchedTweetId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
