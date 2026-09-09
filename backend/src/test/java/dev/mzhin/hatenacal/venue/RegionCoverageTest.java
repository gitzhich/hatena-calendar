package dev.mzhin.hatenacal.venue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 都道府県の網羅（FR-10 / ADR-0022）。
 *
 * <p>この表は今後も編集される。<b>1 県消えても、別の地方に付け替わっても
 * 落ちるようにする。</b> {@link RegionResolverTest} が実データの形を守るのに対し、
 * こちらは<b>8 地方区分という外部の基準</b>との一致を守る。
 *
 * <p>区分は一般的な 8 地方（三重は近畿）に従う。
 */
class RegionCoverageTest {

    /** 8 地方区分。<b>実装ではなく基準そのものを書く。</b> */
    private static final Map<Region, List<String>> STANDARD = Map.of(
            Region.HOKKAIDO, List.of("北海道"),
            Region.TOHOKU, List.of("青森", "岩手", "宮城", "秋田", "山形", "福島"),
            Region.KANTO, List.of("茨城", "栃木", "群馬", "埼玉", "千葉", "東京", "神奈川"),
            Region.CHUBU, List.of("新潟", "富山", "石川", "福井", "山梨", "長野",
                    "岐阜", "静岡", "愛知"),
            Region.KINKI, List.of("三重", "滋賀", "京都", "大阪", "兵庫", "奈良", "和歌山"),
            Region.CHUGOKU, List.of("鳥取", "島根", "岡山", "広島", "山口"),
            Region.SHIKOKU, List.of("徳島", "香川", "愛媛", "高知"),
            Region.KYUSHU, List.of("福岡", "佐賀", "長崎", "熊本", "大分", "宮崎",
                    "鹿児島", "沖縄"));

    private static List<String> allPrefectures() {
        return STANDARD.values().stream().flatMap(List::stream).toList();
    }

    @Test
    @DisplayName("47 都道府県を数え落としていない")
    void countsFortySeven() {
        assertThat(allPrefectures()).hasSize(47).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("47 都道府県すべてが判定できる")
    void everyPrefectureResolves() {
        List<String> unresolved = allPrefectures().stream()
                .filter(p -> RegionResolver.of(p + "・どこかのホール") == Region.UNKNOWN)
                .toList();

        assertThat(unresolved)
                .as("表から落ちた県は、その県の公演がすべて手動の訂正待ちになる")
                .isEmpty();
    }

    @Test
    @DisplayName("どの県も正しい地方に入っている")
    void everyPrefectureMapsToItsRegion() {
        Map<Region, List<String>> actual = allPrefectures().stream()
                .collect(Collectors.groupingBy(p -> RegionResolver.of(p + "・どこかのホール")));

        assertThat(actual)
                .as("付け替えが起きると、遠征の色が静かに入れ替わる")
                .containsExactlyInAnyOrderEntriesOf(STANDARD);
    }

    @Test
    @DisplayName("県名に「県」が付いていても同じ地方になる")
    void suffixDoesNotChangeTheRegion() {
        for (String prefecture : allPrefectures()) {
            if (prefecture.equals("北海道")) {
                continue; // 「道」は接尾辞ではない
            }
            String suffix = switch (prefecture) {
                case "東京" -> "都";
                case "大阪", "京都" -> "府";
                default -> "県";
            };
            assertThat(RegionResolver.of(prefecture + suffix + "・どこかのホール"))
                    .as("%s%s", prefecture, suffix)
                    .isEqualTo(RegionResolver.of(prefecture + "・どこかのホール"));
        }
    }

    @Test
    @DisplayName("地方の識別子を過不足なく使っている")
    void usesEveryDomesticRegion() {
        assertThat(STANDARD.keySet())
                .as("国内の地方が 1 つでも使われていなければ、区分の設計と実装がずれている")
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(Region.values())
                                .filter(r -> r != Region.OVERSEAS && r != Region.UNKNOWN)
                                .toList());
    }
}
