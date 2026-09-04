package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 実サンプル 16 件に対する抽出テスト。期待値は
 * docs/x-integration.md 第 5.11 節。
 *
 * <p>サンプルは docs/x-post-sample/ の写しを test/resources に置いている。
 * 実データにしか現れない崩れ方（半角カナ、3 種類の括弧、曜日の誤記、
 * 同日 2 枠、住所行の混入）を検証するためで、架空データでは代替できない
 * （ADR-0015）。
 */
class PostParserSampleTest {

    private final PostParser parser = new PostParser();

    /** 投稿日時。告知は公演の 1〜2 か月前に出る想定で 2026-08-01 を基準にする。 */
    private static final OffsetDateTime POSTED =
            OffsetDateTime.of(2026, 8, 1, 12, 0, 0, 0, ZoneOffset.ofHours(9));

    private String sample(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/x-post-sample/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<ParsedAppearance> extract(String name) throws IOException {
        ParseResult result = parser.parse(sample(name), POSTED);
        assertThat(result).isInstanceOf(ParseResult.Extracted.class);
        return ((ParseResult.Extracted) result).appearances();
    }

    private ParsedAppearance only(String name) throws IOException {
        List<ParsedAppearance> list = extract(name);
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    @Nested
    @DisplayName("抽出する 9 件")
    class Extracted {

        @Test
        @DisplayName("2.txt 枠の 📍 がない告知はヘッダ会場をそのまま使う")
        void sample2() throws IOException {
            ParsedAppearance a = only("2.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 10, 12));
            assertThat(a.venueName()).isEqualTo("東京・渋谷CLUB QUATTRO");
            assertThat(a.eventName()).isEqualTo("『ORANGE CHEER』");
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(16, 45));
            assertThat(a.performanceEndTime()).isEqualTo(LocalTime.of(17, 5));
            assertThat(a.merchStartTime()).isEqualTo(LocalTime.of(17, 15));
            assertThat(a.merchEndTime()).isEqualTo(LocalTime.of(18, 20));
            assertThat(a.ticketUrl())
                    .isEqualTo("https://livepocket.jp/e/orange-cheer_261012");
        }

        @Test
        @DisplayName("3.txt 半角カギカッコのイベント名")
        void sample3() throws IOException {
            ParsedAppearance a = only("3.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 9, 4));
            assertThat(a.venueName()).isEqualTo("愛知・NAGOYA ReNY limited");
            assertThat(a.eventName()).isEqualTo("｢IGNITION-狂騒-｣");
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(21, 5));
            assertThat(a.merchStartTime()).isEqualTo(LocalTime.of(21, 30));
            assertThat(a.ticketUrl()).isEqualTo("https://t-dv.com/IGNITION-Kyosou-0904");
        }

        @Test
        @DisplayName("4.txt 主催者名が括弧の外に付くイベント名")
        void sample4() throws IOException {
            ParsedAppearance a = only("4.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 9, 16));
            assertThat(a.venueName()).isEqualTo("愛知・大須RADHALL");
            assertThat(a.eventName()).isEqualTo("lonlium pre.『LONELY KIDS』");
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(19, 50));
            assertThat(a.merchStartTime()).isEqualTo(LocalTime.of(21, 25));
        }

        @Test
        @DisplayName("5.txt 住所行が挟まっても、枠の 📍 をステージ名として連結する")
        void sample5() throws IOException {
            ParsedAppearance a = only("5.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 9, 6));
            assertThat(a.venueName())
                    .isEqualTo("千葉・草ぶえの丘(千葉 佐倉) / Orange Shelter");
            assertThat(a.eventName()).isEqualTo("「くさのねアイドルフェスティバル2026」");
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(14, 40));
            assertThat(a.merchStartTime()).isEqualTo(LocalTime.of(15, 50));
            assertThat(a.ticketUrl()).isEqualTo("http://kusanoneidolfes.com/#ticket");
        }

        @Test
        @DisplayName("6.txt 1 投稿から 2 行。サーキット形式は会場の羅列を捨てる")
        void sample6() throws IOException {
            List<ParsedAppearance> list = extract("6.txt");
            assertThat(list).hasSize(2);

            ParsedAppearance first = list.get(0);
            assertThat(first.appearanceDate()).isEqualTo(LocalDate.of(2026, 8, 25));
            assertThat(first.venueName()).isEqualTo("愛知・NAGOYA ReNY limited");
            assertThat(first.eventName()).isEqualTo("『DERAX JAM~ DERA MAXIMUM JAM ~』");
            assertThat(first.performanceStartTime()).isEqualTo(LocalTime.of(16, 35));
            assertThat(first.merchStartTime()).isEqualTo(LocalTime.of(17, 25));

            ParsedAppearance second = list.get(1);
            assertThat(second.appearanceDate()).isEqualTo(LocalDate.of(2026, 8, 25));
            assertThat(second.venueName()).isEqualTo("愛知・RADHALL");
            assertThat(second.performanceStartTime()).isEqualTo(LocalTime.of(19, 50));
            assertThat(second.merchStartTime()).isEqualTo(LocalTime.of(21, 25));
        }

        @Test
        @DisplayName("6.txt 8/26 は行を作らない。2 日目のタイムテーブルが告知されていない")
        void sample6DoesNotCreateSecondDay() throws IOException {
            assertThat(extract("6.txt"))
                    .extracting(ParsedAppearance::appearanceDate)
                    .containsOnly(LocalDate.of(2026, 8, 25));
        }

        @Test
        @DisplayName("14.txt 個別告知。16.txt の 1 ブロック目と同じ公演を指す")
        void sample14() throws IOException {
            ParsedAppearance a = only("14.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(a.venueName()).isEqualTo("東京・渋谷DESEO");
            assertThat(a.eventName()).isEqualTo("「KAMAITACI Pre.\"つむじ風\"」");
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(13, 30));
            assertThat(a.performanceEndTime()).isEqualTo(LocalTime.of(14, 0));
            assertThat(a.merchStartTime()).isEqualTo(LocalTime.of(15, 50));
            assertThat(a.merchEndTime()).isEqualTo(LocalTime.of(17, 10));
        }

        @Test
        @DisplayName("15.txt 冒頭にもう一方の会場名があっても、会場は 📍 からしか取らない")
        void sample15() throws IOException {
            ParsedAppearance a = only("15.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(a.venueName()).isEqualTo("東京・白金高輪SELENEb2");
            assertThat(a.eventName()).isEqualTo("「SELENE SUMMER FES」-DAY1-");
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(19, 35));
            assertThat(a.merchStartTime()).isEqualTo(LocalTime.of(20, 30));
        }

        @Test
        @DisplayName("16.txt 1 投稿に 2 イベント。ブロックごとにイベント名と会場を取る")
        void sample16() throws IOException {
            List<ParsedAppearance> list = extract("16.txt");
            assertThat(list).hasSize(2);

            ParsedAppearance first = list.get(0);
            assertThat(first.appearanceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(first.venueName()).isEqualTo("東京・渋谷DESEO");
            assertThat(first.eventName()).isEqualTo("「KAMAITACI Pre.\"つむじ風\"」");
            assertThat(first.performanceStartTime()).isEqualTo(LocalTime.of(13, 30));
            assertThat(first.merchStartTime()).isEqualTo(LocalTime.of(15, 50));

            ParsedAppearance second = list.get(1);
            assertThat(second.appearanceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(second.venueName()).isEqualTo("東京・白金高輪SELENEb2");
            assertThat(second.eventName()).isEqualTo("「SELENE SUMMER FES」-DAY1-");
            assertThat(second.performanceStartTime()).isEqualTo(LocalTime.of(19, 35));
            assertThat(second.merchStartTime()).isEqualTo(LocalTime.of(20, 30));
        }

        @Test
        @DisplayName("16.txt 会場を連結しない。2 ブロック目に 1 ブロック目の会場を混ぜない")
        void sample16KeepsBlocksApart() throws IOException {
            assertThat(extract("16.txt"))
                    .extracting(ParsedAppearance::venueName)
                    .containsExactly("東京・渋谷DESEO", "東京・白金高輪SELENEb2");
        }

        @Test
        @DisplayName("16.txt は 14.txt / 15.txt と同じ 2 件を指す。まとめ告知でも結果が変わらない")
        void sample16MatchesIndividualPosts() throws IOException {
            List<ParsedAppearance> combined = extract("16.txt");
            assertThat(combined.get(0))
                    .usingRecursiveComparison().ignoringFields("ticketUrl")
                    .isEqualTo(only("14.txt"));
            assertThat(combined.get(1))
                    .usingRecursiveComparison().ignoringFields("ticketUrl")
                    .isEqualTo(only("15.txt"));
        }

        @Test
        @DisplayName("12.txt 前日告知。タイトルに「解禁」がなくても構造が同じなら抽出する")
        void sample12() throws IOException {
            ParsedAppearance a = only("12.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 8, 19));
            assertThat(a.venueName()).isEqualTo("愛知・大須RAD HALL");
            assertThat(a.eventName()).isEqualTo("『RAD iD LIVE-NO残業DAY-』");
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(21, 25));
            assertThat(a.merchStartTime()).isEqualTo(LocalTime.of(22, 0));
            assertThat(a.ticketUrl()).isEqualTo(
                    "https://ticketdive.com/event/RADiD-LIVE-NOZangyoDAY-0819");
        }
    }

    @Nested
    @DisplayName("抽出しない 7 件")
    class NotExtracted {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"1.txt", "7.txt", "8.txt", "9.txt", "10.txt", "11.txt",
                "13.txt"})
        @DisplayName("対象外の投稿型は Unparsed になる")
        void unparsed(String name) throws IOException {
            assertThat(parser.parse(sample(name), POSTED))
                    .isInstanceOf(ParseResult.Unparsed.class);
        }

        @Test
        @DisplayName("1.txt 出演時刻が未確定の情報解禁。将来対応（第 11.1 節）")
        void sample1() throws IOException {
            ParseResult r = parser.parse(sample("1.txt"), POSTED);
            assertThat(((ParseResult.Unparsed) r).reason()).contains("🎤");
        }

        @Test
        @DisplayName("7.txt 次回予告つきのお礼投稿。緩い条件なら通ってしまう投稿")
        void sample7() throws IOException {
            String body = sample("7.txt");
            assertThat(body).contains("XINXIN", "📍", "8/26(水)");
            assertThat(parser.parse(body, POSTED))
                    .isInstanceOf(ParseResult.Unparsed.class);
        }

        @Test
        @DisplayName("11.txt 出演時間の訂正。🎤 はあるが日付がない（第 11.2 節）")
        void sample11() throws IOException {
            ParseResult r = parser.parse(sample("11.txt"), POSTED);
            assertThat(((ParseResult.Unparsed) r).reason()).contains("公演日");
        }
    }
}
