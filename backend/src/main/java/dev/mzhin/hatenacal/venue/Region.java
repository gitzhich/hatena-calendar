package dev.mzhin.hatenacal.venue;

/**
 * 会場の地域（FR-10 / ADR-0022）。8 地方 + 海外 + 不明。
 *
 * <p><b>表示名も色もここでは決めない。</b> API はこの識別子を返すだけで、
 * どう見せるかはクライアントの決定（docs/api.md「期間内の出演情報一覧」）。
 */
public enum Region {
    HOKKAIDO,
    TOHOKU,
    KANTO,
    CHUBU,
    KINKI,
    CHUGOKU,
    SHIKOKU,
    KYUSHU,
    /** 国内でないと判定できたもの。 */
    OVERSEAS,
    /** 判定できないもの。<b>推測で近い地方へ寄せない。</b> */
    UNKNOWN
}
