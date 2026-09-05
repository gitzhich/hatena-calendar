package dev.mzhin.hatenacal.ingestion;

import java.util.List;
import java.util.Objects;

/**
 * 1 回の取得の結果。
 *
 * @param posts     取得した投稿。API は新しい順に返すため、この時点でも新しい順
 * @param nextToken 次ページの {@code pagination_token}。無ければ {@code null}
 */
public record FetchResult(List<SourcePost> posts, String nextToken) {

    public FetchResult {
        Objects.requireNonNull(posts, "posts は必須です");
        posts = List.copyOf(posts);
    }

    public static FetchResult empty() {
        return new FetchResult(List.of(), null);
    }

    /**
     * 課金対象のリソース数。
     *
     * <p>課金はリクエスト数ではなく<b>返却されたリソース数</b>に対して発生する。
     * 5 件取得で使用量がちょうど 5 増えることを実測で確認している
     * （docs/x-integration.md「記録」）。
     */
    public int resourceCount() {
        return posts.size();
    }

    public boolean hasNextPage() {
        return nextToken != null && !nextToken.isBlank();
    }
}
