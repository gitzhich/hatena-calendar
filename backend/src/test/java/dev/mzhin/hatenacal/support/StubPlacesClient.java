package dev.mzhin.hatenacal.support;

import dev.mzhin.hatenacal.venue.PlacesClient;
import dev.mzhin.hatenacal.venue.PlacesException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Places API の代役。
 *
 * <p><b>実 API を叩かずに、解決の経路だけを検証する。</b> HTTP そのものは
 * {@code PlacesHttpClientTest} が実サーバで見ている。ここで確かめたいのは
 * 「いつ叩き、結果をどう記録するか」（ADR-0022「暴走と無駄叩きを防ぐ」）。
 *
 * <p><b>「見つからない」と「到達できない」を撃ち分けられる</b>ようにしてある。
 * 両者を取り違えると再試行の間隔が壊れるため、テストで分けて表明する。
 */
public final class StubPlacesClient implements PlacesClient {

    private final Map<String, String> answers = new HashMap<>();
    private final List<String> queries = new ArrayList<>();
    private PlacesException failure;

    /** この問い合わせには place_id を返す。登録しない問い合わせは「見つからない」。 */
    public void answer(String query, String placeId) {
        answers.put(query, placeId);
    }

    /** 以後の呼び出しをすべて到達不能にする。 */
    public void failAll(int status) {
        failure = new PlacesException("stub: HTTP " + status, status);
    }

    /** 実際に送られた問い合わせ。順序も含めて見る。 */
    public List<String> queries() {
        return List.copyOf(queries);
    }

    public void reset() {
        answers.clear();
        queries.clear();
        failure = null;
    }

    @Override
    public Optional<String> findPlaceId(String query) {
        queries.add(query);
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(answers.get(query));
    }
}
