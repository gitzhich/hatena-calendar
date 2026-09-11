package dev.mzhin.hatenacal.venue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 会場名からの地域判定（FR-10 / ADR-0022）。
 *
 * <p>実データに出てくる形を固定する。<b>「・」の前が都道府県とは限らない</b>ことが
 * この判定の難所で、市名と国名が実在する。
 */
class RegionResolverTest {

    @Nested
    @DisplayName("実データに出てくる会場")
    class RealSamples {

        @Test
        @DisplayName("都道府県はそのまま地方になる")
        void prefectures() {
            assertThat(RegionResolver.of("愛知・大須RADHALL")).isEqualTo(Region.CHUBU);
            assertThat(RegionResolver.of("東京・品川インターシティホール")).isEqualTo(Region.KANTO);
            assertThat(RegionResolver.of("大阪・心斎橋SUNHALL")).isEqualTo(Region.KINKI);
        }

        @Test
        @DisplayName("市名でも判定する。都道府県の表だけでは落ちる")
        void cityName() {
            assertThat(RegionResolver.of("金沢・REDSUN"))
                    .as("金沢は市名。石川県＝中部に寄せる")
                    .isEqualTo(Region.CHUBU);
        }

        @Test
        @DisplayName("国名は海外にする")
        void overseas() {
            assertThat(RegionResolver.of("韓国・SETi LIVE HALL")).isEqualTo(Region.OVERSEAS);
        }

        @Test
        @DisplayName("括弧やスラッシュが続いても先頭の地名で決まる")
        void trailingNoise() {
            assertThat(RegionResolver.of("千葉・草ぶえの丘(千葉 佐倉) / Orange Shelter"))
                    .isEqualTo(Region.KANTO);
            assertThat(RegionResolver.of("愛知・NAGOYA ReNY limited & 栄Zephyr Hall"))
                    .isEqualTo(Region.CHUBU);
        }

        @Test
        @DisplayName("枠のステージ名だけの表記は判定しない")
        void stageNameOnly() {
            assertThat(RegionResolver.of("ドラゴンステージ")).isEqualTo(Region.UNKNOWN);
            assertThat(RegionResolver.of("Orange Shelter")).isEqualTo(Region.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("表記のゆれ")
    class Variations {

        @Test
        @DisplayName("都・府・県が付いていても判定する")
        void withSuffix() {
            assertThat(RegionResolver.of("東京都・渋谷DESEO")).isEqualTo(Region.KANTO);
            assertThat(RegionResolver.of("大阪府・心斎橋SUNHALL")).isEqualTo(Region.KINKI);
            assertThat(RegionResolver.of("愛知県・大須RADHALL")).isEqualTo(Region.CHUBU);
        }

        @Test
        @DisplayName("北海道の「道」を接尾辞として落とさない")
        void hokkaido() {
            assertThat(RegionResolver.of("北海道・札幌ペニーレーン24"))
                    .isEqualTo(Region.HOKKAIDO);
        }

        @Test
        @DisplayName("全角の中黒に正規化してから区切る")
        void halfwidthSeparator() {
            assertThat(RegionResolver.of("愛知･大須RADHALL")).isEqualTo(Region.CHUBU);
        }
    }

    @Nested
    @DisplayName("判定しないもの")
    class Unknown {

        @Test
        @DisplayName("「中国」は倒さない。中国地方と国名の区別が付かない")
        void chinaIsAmbiguous() {
            assertThat(RegionResolver.of("中国・どこかのホール"))
                    .as("どちらに倒しても半分は誤る。人が決める")
                    .isEqualTo(Region.UNKNOWN);
        }

        @Test
        @DisplayName("表に無い地名は推測で寄せない")
        void unknownPlace() {
            assertThat(RegionResolver.of("架空県・どこかのホール")).isEqualTo(Region.UNKNOWN);
        }

        @Test
        @DisplayName("会場が無い・区切りが無い")
        void missing() {
            assertThat(RegionResolver.of(null)).isEqualTo(Region.UNKNOWN);
            assertThat(RegionResolver.of("")).isEqualTo(Region.UNKNOWN);
            assertThat(RegionResolver.of("・先頭が区切り")).isEqualTo(Region.UNKNOWN);
        }
    }

    /**
     * 地名そのものからの判定（ADR-0022「会場が未定でも地域は持つ」）。
     *
     * <p>会場が未定のときに使う入口。<b>会場名からの判定と同じ表を通る</b>ことを固定する。
     */
    @Nested
    @DisplayName("地名そのものから判定する")
    class FromPlaceName {

        @Test
        @DisplayName("会場名からの判定と同じ結果になる")
        void matchesVenueNameResolution() {
            assertThat(RegionResolver.ofPlace("東京")).isEqualTo(RegionResolver.of("東京・会場"));
            assertThat(RegionResolver.ofPlace("金沢")).isEqualTo(RegionResolver.of("金沢・会場"));
            assertThat(RegionResolver.ofPlace("韓国")).isEqualTo(RegionResolver.of("韓国・会場"));
        }

        @Test
        @DisplayName("都道府県・主要都市・海外のどれも引ける")
        void coversAllTables() {
            assertThat(RegionResolver.ofPlace("東京")).isEqualTo(Region.KANTO);
            assertThat(RegionResolver.ofPlace("金沢")).isEqualTo(Region.CHUBU);
            assertThat(RegionResolver.ofPlace("韓国")).isEqualTo(Region.OVERSEAS);
            assertThat(RegionResolver.ofPlace("東京都")).isEqualTo(Region.KANTO);
        }

        @Test
        @DisplayName("地名かどうかを判定できる")
        void detectsPlaceName() {
            assertThat(RegionResolver.isPlaceName("東京")).isTrue();
            assertThat(RegionResolver.isPlaceName("恵比寿LIQUIDROOM"))
                    .as("会場名を地域の行にすると、地図リンクを出せる会場を出せなくする")
                    .isFalse();
            assertThat(RegionResolver.isPlaceName("中国"))
                    .as("中国地方と国名の区別が付かないので地名として扱わない")
                    .isFalse();
            assertThat(RegionResolver.isPlaceName(null)).isFalse();
            assertThat(RegionResolver.isPlaceName("")).isFalse();
        }

        @Test
        @DisplayName("会場名の先頭から地名を切り出せる")
        void extractsPlace() {
            assertThat(RegionResolver.placeOf("東京・渋谷音楽堂/Shibuya Milkyway")).isEqualTo("東京");
            assertThat(RegionResolver.placeOf("ドラゴンステージ"))
                    .as("・ が無ければ地名は書かれていない")
                    .isNull();
        }
    }
}
