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
import org.hibernate.annotations.CreationTimestamp;

/**
 * 取り込み済み投稿の記録（docs/data-model.md 第 4.2 節）。
 *
 * <p><b>投稿本文を保持しない</b>（LR-02）。未処理投稿を手で処理する管理者は、
 * tweet_id から組み立てた X 投稿 URL を開いて原文を X 上で読む。
 *
 * <p>tweet_id の UNIQUE 制約が再処理時の冪等性の要（FR-23, FR-41）。
 */
@Entity
@Table(name = "ingested_post")
public class IngestedPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_account_id", nullable = false)
    private Long sourceAccountId;

    /**
     * X の投稿 ID。
     *
     * <p>BIGINT で持つ。TEXT だと「最大値の取得」が文字列比較になり、
     * 桁数が変わった瞬間に取得位置が巻き戻る。巻き戻りは再課金に直結する
     * （docs/data-model.md 第 4.1 節）。
     */
    @Column(name = "tweet_id", nullable = false, unique = true)
    private Long tweetId;

    @Column(name = "posted_at", nullable = false)
    private OffsetDateTime postedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IngestedPostStatus status;

    @CreationTimestamp
    @Column(name = "ingested_at", nullable = false, updatable = false)
    private OffsetDateTime ingestedAt;

    protected IngestedPost() {
        // JPA 用
    }

    public Long getId() {
        return id;
    }

    public Long getSourceAccountId() {
        return sourceAccountId;
    }

    public Long getTweetId() {
        return tweetId;
    }

    public OffsetDateTime getPostedAt() {
        return postedAt;
    }

    public IngestedPostStatus getStatus() {
        return status;
    }

    public OffsetDateTime getIngestedAt() {
        return ingestedAt;
    }

    /**
     * 処理済みにする。
     *
     * <p>UNPARSED のときだけ進める。既に REGISTERED なら変更しない
     * （docs/api.md 第 5.2 節）。同じ投稿から 2 件目の出演情報を作る場合に
     * 状態を巻き戻さないため。
     */
    public void markRegistered() {
        if (status == IngestedPostStatus.UNPARSED) {
            status = IngestedPostStatus.REGISTERED;
        }
    }

    /** 出演告知ではないと判断した（FR-25）。以降は未処理一覧に現れない。 */
    public void markExcluded() {
        status = IngestedPostStatus.EXCLUDED;
    }
}
