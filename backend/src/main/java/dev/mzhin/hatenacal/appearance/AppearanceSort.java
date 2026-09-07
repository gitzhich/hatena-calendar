package dev.mzhin.hatenacal.appearance;

import org.springframework.data.domain.Sort;

/**
 * 点検一覧の並び順（FR-24 / docs/api.md「出演情報の一覧と個別取得（点検用）」）。
 *
 * <p><b>並べ替えの対象を列挙で閉じる。</b> クエリの文字列を {@link Sort} へそのまま
 * 渡すと、返していない列や関連まで並べ替えの対象になり、契約に無い順序で DB を引かせられる。
 *
 * <p>どの順序も<b>最後に {@code id} を足す</b>。同じ値の行が並ぶと順序が決まらず、
 * ページの境界で取りこぼしと重複が出る。
 *
 * <p>出演開始時刻は未設定がありうるので<b>常に最後へ置く</b>（{@code NULLS LAST}）。
 * 昇順と降順で未設定の位置が入れ替わると、同じ「時刻未定」の行が
 * 並べ替えのたびに端から端へ飛ぶ。
 */
public enum AppearanceSort {

    /** 公演日時の新しい順。**既定**。 */
    DATE_DESC(Sort.by(
            Sort.Order.desc("appearanceDate"),
            Sort.Order.desc("performanceStartTime").nullsLast(),
            Sort.Order.desc("id"))),

    /** 公演日時の古い順。 */
    DATE_ASC(Sort.by(
            Sort.Order.asc("appearanceDate"),
            Sort.Order.asc("performanceStartTime").nullsLast(),
            Sort.Order.asc("id"))),

    /** 登録の新しい順。取り込み直後の点検に使う。 */
    CREATED_DESC(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),

    /** 登録の古い順。 */
    CREATED_ASC(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));

    private final Sort sort;

    AppearanceSort(Sort sort) {
        this.sort = sort;
    }

    Sort toSort() {
        return sort;
    }

    /**
     * クエリの値から解決する。<b>知らない値は既定へ倒す。</b>
     *
     * <p>並び順は表示の都合でしかない。古いリンクを開いただけで画面が止まるより、
     * 既定で出たほうがよい。範囲外の {@code page} / {@code size} を丸めるのと
     * 同じ考え方（docs/api.md「出演情報の一覧と個別取得（点検用）」）。
     */
    public static AppearanceSort from(String value) {
        if (value != null) {
            for (AppearanceSort candidate : values()) {
                if (candidate.name().equalsIgnoreCase(value)) {
                    return candidate;
                }
            }
        }
        return DATE_DESC;
    }
}
