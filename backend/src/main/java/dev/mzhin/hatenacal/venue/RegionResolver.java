package dev.mzhin.hatenacal.venue;

import java.text.Normalizer;
import java.util.Map;

/**
 * 会場名から地域を判定する（FR-10 / ADR-0022）。
 *
 * <p>公式の告知は {@code {地名}・{会場名}} の形で書く
 * （docs/x-integration.md「会場」）。この {@code ・} より前を地名として読む。
 *
 * <p><b>地名は都道府県とは限らない。</b> 実データに {@code 金沢・REDSUN}（市名）と
 * {@code 韓国・SETi LIVE HALL}（国名）がある。都道府県の表だけでは落ちるため、
 * 主要都市と海外の表を併せ持つ。
 *
 * <p><b>表に無いものを推測で寄せない。</b> {@link Region#UNKNOWN} のまま返し、
 * 管理者が直す。誤った地域は誤った色として利用者に届く。
 */
public final class RegionResolver {

    /** 都道府県 → 地方。8 地方区分。 */
    private static final Map<String, Region> PREFECTURES = Map.ofEntries(
            Map.entry("北海道", Region.HOKKAIDO),

            Map.entry("青森", Region.TOHOKU), Map.entry("岩手", Region.TOHOKU),
            Map.entry("宮城", Region.TOHOKU), Map.entry("秋田", Region.TOHOKU),
            Map.entry("山形", Region.TOHOKU), Map.entry("福島", Region.TOHOKU),

            Map.entry("茨城", Region.KANTO), Map.entry("栃木", Region.KANTO),
            Map.entry("群馬", Region.KANTO), Map.entry("埼玉", Region.KANTO),
            Map.entry("千葉", Region.KANTO), Map.entry("東京", Region.KANTO),
            Map.entry("神奈川", Region.KANTO),

            Map.entry("新潟", Region.CHUBU), Map.entry("富山", Region.CHUBU),
            Map.entry("石川", Region.CHUBU), Map.entry("福井", Region.CHUBU),
            Map.entry("山梨", Region.CHUBU), Map.entry("長野", Region.CHUBU),
            Map.entry("岐阜", Region.CHUBU), Map.entry("静岡", Region.CHUBU),
            Map.entry("愛知", Region.CHUBU),

            Map.entry("三重", Region.KINKI), Map.entry("滋賀", Region.KINKI),
            Map.entry("京都", Region.KINKI), Map.entry("大阪", Region.KINKI),
            Map.entry("兵庫", Region.KINKI), Map.entry("奈良", Region.KINKI),
            Map.entry("和歌山", Region.KINKI),

            Map.entry("鳥取", Region.CHUGOKU), Map.entry("島根", Region.CHUGOKU),
            Map.entry("岡山", Region.CHUGOKU), Map.entry("広島", Region.CHUGOKU),
            Map.entry("山口", Region.CHUGOKU),

            Map.entry("徳島", Region.SHIKOKU), Map.entry("香川", Region.SHIKOKU),
            Map.entry("愛媛", Region.SHIKOKU), Map.entry("高知", Region.SHIKOKU),

            Map.entry("福岡", Region.KYUSHU), Map.entry("佐賀", Region.KYUSHU),
            Map.entry("長崎", Region.KYUSHU), Map.entry("熊本", Region.KYUSHU),
            Map.entry("大分", Region.KYUSHU), Map.entry("宮崎", Region.KYUSHU),
            Map.entry("鹿児島", Region.KYUSHU), Map.entry("沖縄", Region.KYUSHU));

    /**
     * 都道府県名と一致しない主要都市。
     *
     * <p><b>都道府県と同じ名前の市は入れない</b>（京都・大阪・広島など）。
     * 上の表で解決するため、二重に持つと片方だけ直す事故が起きる。
     */
    private static final Map<String, Region> CITIES = Map.ofEntries(
            Map.entry("札幌", Region.HOKKAIDO),
            Map.entry("仙台", Region.TOHOKU),
            Map.entry("さいたま", Region.KANTO),
            Map.entry("横浜", Region.KANTO),
            Map.entry("川崎", Region.KANTO),
            Map.entry("名古屋", Region.CHUBU),
            Map.entry("金沢", Region.CHUBU),
            Map.entry("浜松", Region.CHUBU),
            Map.entry("神戸", Region.KINKI),
            Map.entry("堺", Region.KINKI),
            Map.entry("高松", Region.SHIKOKU),
            Map.entry("北九州", Region.KYUSHU),
            Map.entry("那覇", Region.KYUSHU));

    /**
     * 海外と判定する地名。
     *
     * <p><b>「中国」を入れない。</b> 中国地方と国名の区別が付かず、
     * どちらに倒しても半分は誤る。{@link Region#UNKNOWN} にして人が決める。
     */
    private static final Map<String, Region> OVERSEAS = Map.ofEntries(
            Map.entry("韓国", Region.OVERSEAS),
            Map.entry("台湾", Region.OVERSEAS),
            Map.entry("香港", Region.OVERSEAS),
            Map.entry("タイ", Region.OVERSEAS),
            Map.entry("シンガポール", Region.OVERSEAS),
            Map.entry("アメリカ", Region.OVERSEAS));

    private RegionResolver() {
    }

    /**
     * @param venueName 会場名（告知の原文）。{@code null} 可
     * @return 判定できた地方。できなければ {@link Region#UNKNOWN}
     */
    public static Region of(String venueName) {
        return ofPlace(placeOf(venueName));
    }

    /**
     * 地名そのものから地域を判定する。
     *
     * <p>{@link #of} が {@code ・} より前を切り出して渡す入口を、外からも使えるようにしたもの。
     * 会場が未定で地名だけが分かっている場合に使う
     * （{@code appearance.area_name} / ADR-0022「会場が未定でも地域は持つ」）。
     *
     * <p><b>表は 1 つ。</b> 会場名からの判定と同じ表を通す。2 つ持つと、片方だけ直したときに
     * 「会場ありなら中部、未定なら不明」のような食い違いが生まれる。
     *
     * @param place 地名（{@code 東京} / {@code 金沢} / {@code 韓国}）。{@code null} 可
     */
    public static Region ofPlace(String place) {
        if (place == null || place.isBlank()) {
            return Region.UNKNOWN;
        }
        Region overseas = OVERSEAS.get(place);
        if (overseas != null) {
            return overseas;
        }
        Region city = CITIES.get(place);
        if (city != null) {
            return city;
        }
        Region prefecture = PREFECTURES.get(place);
        if (prefecture != null) {
            return prefecture;
        }
        // 「東京都」「大阪府」「愛知県」のように接尾辞が付いた表記を拾う。
        // 「北海道」は接尾辞ではないので落とさない（表に直接ある）
        if (place.length() > 1 && "都府県".indexOf(place.charAt(place.length() - 1)) >= 0) {
            Region trimmed = PREFECTURES.get(place.substring(0, place.length() - 1));
            if (trimmed != null) {
                return trimmed;
            }
        }
        return Region.UNKNOWN;
    }

    /**
     * 地名そのものか。
     *
     * <p>会場ではなく地域だけを表す行（{@code 東京}）を作ってよいかの判定に使う。
     * <b>表に載っている地名と判定できたときだけ true。</b>
     * {@code 恵比寿LIQUIDROOM} のような会場名を地域と取り違えない。
     */
    public static boolean isPlaceName(String value) {
        return ofPlace(value) != Region.UNKNOWN;
    }

    /**
     * 会場名の先頭にある地名を取り出す。
     *
     * <p>{@code ・} が無ければ地名は書かれていない。実データでは
     * {@code ドラゴンステージ} のようなステージ名だけの表記がこれに当たる。
     */
    public static String placeOf(String venueName) {
        if (venueName == null) {
            return null;
        }
        String normalized = Normalizer.normalize(venueName, Normalizer.Form.NFKC);
        int separator = normalized.indexOf('・');
        if (separator <= 0) {
            return null;
        }
        String place = normalized.substring(0, separator).trim();
        return place.isEmpty() ? null : place;
    }
}
