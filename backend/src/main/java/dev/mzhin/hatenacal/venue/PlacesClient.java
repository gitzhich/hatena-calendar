package dev.mzhin.hatenacal.venue;

import java.util.Optional;

/**
 * Google Places から {@code place_id} だけを引く（ADR-0022）。
 *
 * <p><b>取るのは place_id のみ。</b> 名前・住所・評価・写真を取得しない。
 * これにより帰属表示（"Powered by Google"）が不要になり、キャッシュ制限の例外にも収まる
 * （docs/security.md T-08 / docs/data-model.md「place_id の扱い」）。
 *
 * <p>インターフェースにしているのは、テストが実 API を叩かずに済むようにするため
 * （{@code XApiClient} と同じ理由）。
 */
public interface PlacesClient {

    /**
     * 会場名で検索し、最上位の候補の {@code place_id} を返す。
     *
     * @param query 会場の代表表記。告知の原文をそのまま渡す
     * @return 見つからなければ empty。<b>推測で近い施設を入れない</b>——
     *         誤った地図リンクはリンクが無いより悪い（docs/security.md T-08）
     * @throws PlacesException Google に到達できない、またはエラーが返った。
     *         <b>「見つからなかった」とは区別する</b>
     */
    Optional<String> findPlaceId(String query);
}
