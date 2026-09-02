package dev.mzhin.hatenacal.ingestion;

/**
 * 情報源から投稿を取得する。
 *
 * <p>取得層をインターフェースで抽象化し、手動登録のみでもアプリが成立する状態を保つ
 * （CLAUDE.md）。X API が落ちても公開カレンダーは動き続ける（NFR-02）。
 */
public interface XApiClient {

    /**
     * 投稿を取得する。
     *
     * @param xUserId         情報源アカウントの数値 ID。username からは解決しない（FR-40）
     * @param window          取得範囲。範囲なしの取得は表現できない
     * @param paginationToken 続きを取るときのトークン。先頭ページなら {@code null}
     * @throws XApiException 取得に失敗したとき。リトライ済みで、これ以上試みない
     */
    FetchResult fetchPosts(long xUserId, FetchWindow window, String paginationToken);
}
