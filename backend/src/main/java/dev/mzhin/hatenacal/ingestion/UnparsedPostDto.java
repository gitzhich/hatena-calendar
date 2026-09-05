package dev.mzhin.hatenacal.ingestion;

import java.time.OffsetDateTime;

/**
 * 未処理投稿（docs/api.md「未処理投稿の一覧」）。
 *
 * <p><b>投稿本文を返さない</b>（LR-02。そもそも保持していない）。
 * 管理者は postUrl を開いて X 上で原文を読む。
 *
 * <p>tweetId は<b>文字列で返す</b>。JavaScript の Number は 53 bit までで、
 * 数値のまま渡すと精度が落ちる。
 */
public record UnparsedPostDto(Long id, String tweetId, String postUrl,
        OffsetDateTime postedAt, OffsetDateTime ingestedAt) {

    public static UnparsedPostDto from(IngestedPost p, String sourceUsername) {
        return new UnparsedPostDto(p.getId(), String.valueOf(p.getTweetId()),
                "https://x.com/%s/status/%d".formatted(sourceUsername, p.getTweetId()),
                p.getPostedAt(), p.getIngestedAt());
    }
}
