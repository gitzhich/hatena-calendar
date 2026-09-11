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
 * 実サンプル 28 件に対する抽出テスト。期待値は
 * docs/x-integration.md「実サンプルでの検証結果」。
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
        return extract(name, POSTED);
    }

    /**
     * 投稿日を指定して抽出する。
     *
     * <p>基準の {@link #POSTED} から 30 日以上前の公演は翌年と解釈されるため
     * （docs/x-integration.md「日付」）、6 月の公演を扱う 18.txt では実際の投稿日に近い値が要る。
     */
    private List<ParsedAppearance> extract(String name, OffsetDateTime postedAt)
            throws IOException {
        ParseResult result = parser.parse(sample(name), postedAt);
        assertThat(result).isInstanceOf(ParseResult.Extracted.class);
        return ((ParseResult.Extracted) result).appearances();
    }

    private ParsedAppearance only(String name) throws IOException {
        List<ParsedAppearance> list = extract(name);
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    @Nested
    @DisplayName("抽出する 21 件")
    class Extracted {

        @Test
        @DisplayName("2.txt 枠の 📍 がない告知はヘッダ会場をそのまま使う")
        void sample2() throws IOException {
            ParsedAppearance a = only("2.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 10, 12));
            assertThat(a.venueName()).isEqualTo("東京・渋谷CLUB QUATTRO");
            assertThat(a.areaName())
                    .as("会場が確定しているので地名は持たない。地域は venue から引ける")
                    .isNull();
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
            assertThat(a.ticketUrl())
                    .as("実 API の本文は t.co の短縮 URL を含む")
                    .isEqualTo("https://t.co/NGGpRDBOgq");
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
            assertThat(a.ticketUrl()).isEqualTo("https://t.co/TQRJrHN9Dj");
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

            assertThat(list).extracting(ParsedAppearance::ticketUrl)
                    .as("チケット URL もブロックの中だけから取る")
                    .containsExactly("https://t.co/NGGpRDBOgq", "https://t.co/TQRJrHN9Dj");
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
            /*
             * ticketUrl は比較から外す。t.co の短縮 URL は投稿ごとに振られるため、
             * 同じ公演でも個別告知とまとめ告知で別の値になりうる。
             * 実害は無い（先に登録された行の値が空欄補完で保たれる）。
             */
            assertThat(combined.get(0))
                    .usingRecursiveComparison().ignoringFields("ticketUrl")
                    .isEqualTo(only("14.txt"));
            assertThat(combined.get(1))
                    .usingRecursiveComparison().ignoringFields("ticketUrl")
                    .isEqualTo(only("15.txt"));
        }

        @Test
        @DisplayName("17.txt 前置きの 📍 を会場にしない。ブロックの開始行より前は捨てる")
        void sample17() throws IOException {
            List<ParsedAppearance> list = extract("17.txt");
            assertThat(list).hasSize(2);
            assertThat(list).extracting(ParsedAppearance::venueName)
                    .as("前置きの「📍大阪・Music Club JANUSと」を拾うと末尾に「と」が残る")
                    .containsExactly("大阪・Music Club JANUS", "大阪・心斎橋SUNHALL");
            assertThat(list).extracting(ParsedAppearance::performanceStartTime)
                    .containsExactly(LocalTime.of(13, 20), LocalTime.of(19, 50));
            assertThat(list).extracting(ParsedAppearance::eventName)
                    .containsExactly("I to U $CREAMing!! 8周年記念 大阪主催「symmetric」",
                            "こぐまカリー主催「Mash UP!」");
            assertThat(list).extracting(ParsedAppearance::ticketUrl)
                    .containsExactly("http://eplus.jp/ayusuku_8th",
                            "https://ticketdive.com/event/kc2026080809");
        }

        @Test
        @DisplayName("18.txt 2 公演が同じ会場。会場が同じでもブロックは別々に扱う")
        void sample18() throws IOException {
            // 前日告知。基準の POSTED（8/1）だと 6/6 が翌年と解釈される
            List<ParsedAppearance> list = extract("18.txt",
                    OffsetDateTime.of(2026, 6, 5, 12, 0, 0, 0, ZoneOffset.ofHours(9)));
            assertThat(list).hasSize(2);
            assertThat(list).extracting(ParsedAppearance::venueName)
                    .as("同じ会場を自分自身と連結しない")
                    .containsExactly("韓国・SETi LIVE HALL", "韓国・SETi LIVE HALL");
            assertThat(list).extracting(ParsedAppearance::eventName)
                    .containsExactly("「 SETi FES vol.33 」",
                            "「XINXIN NEKIRU ジエメイ 3MANLIVE in KOREA」");
            assertThat(list).extracting(ParsedAppearance::performanceStartTime)
                    .containsExactly(LocalTime.of(13, 45), LocalTime.of(19, 20));
            assertThat(list).extracting(ParsedAppearance::merchStartTime)
                    .containsExactly(LocalTime.of(14, 40), LocalTime.of(21, 10));
            assertThat(list).extracting(ParsedAppearance::ticketUrl)
                    .containsExactly("http://tiget.net/events/490634",
                            "http://tiget.net/events/490990");
        }

        @Test
        @DisplayName("19.txt ⏰ が落ちた告知。OPEN 行を境界にして販売期間の日付を除く")
        void sample19() throws IOException {
            ParsedAppearance a = only("19.txt");
            assertThat(a.appearanceDate())
                    .as("⏰ が無いと探索範囲が ▪️ まで広がり、"
                            + "販売期間の 8/21 と 8/28 が公演日の候補に混ざる")
                    .isEqualTo(LocalDate.of(2026, 10, 22));
            assertThat(a.venueName()).isEqualTo("愛知・NAGOYA JAMMIN'");
            assertThat(a.eventName())
                    .as("主催者が別行にあると括弧の行だけが残る（docs/x-integration.md「イベント名」の既知の制限）")
                    .isEqualTo("「新進火花」");
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(20, 5));
            assertThat(a.performanceEndTime()).isEqualTo(LocalTime.of(20, 40));
            assertThat(a.merchStartTime()).isEqualTo(LocalTime.of(20, 50));
            assertThat(a.merchEndTime()).isEqualTo(LocalTime.of(22, 30));
            assertThat(a.ticketUrl()).isEqualTo("https://t-dv.com/xinhiba1022");
        }

        @Test
        @DisplayName("20.txt 括弧のないイベント名。会場行の次から空行までをまとめる")
        void sample20() throws IOException {
            ParsedAppearance a = only("20.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 10, 10));
            assertThat(a.venueName()).isEqualTo("金沢・REDSUN");
            assertThat(a.eventName())
                    .as("3 行に分かれており括弧が 1 つも無い")
                    .isEqualTo("HATENA CREATION Presents ジエメイ VS XINXIN BANDSET 2MAN LIVE");
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(16, 0));
            assertThat(a.performanceEndTime()).isEqualTo(LocalTime.of(16, 45));
            assertThat(a.merchStartTime()).isEqualTo(LocalTime.of(18, 15));
            assertThat(a.merchEndTime()).isEqualTo(LocalTime.of(19, 45));
            assertThat(a.ticketUrl()).isEqualTo("https://t-dv.com/jiexin1010");
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

        @Test
        @DisplayName("1.txt タイムテーブル未確定。会場が羅列なので空欄で登録する")
        void sample1() throws IOException {
            ParsedAppearance a = only("1.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 9, 15));
            assertThat(a.eventName()).isEqualTo("#ﾆｷﾌﾟﾚ『カンシャサイ。-秋-』");
            assertThat(a.venueName())
                    .as("7 会場が / で並ぶ。どこに出るかはタイムテーブルまで決まらない")
                    .isNull();
            assertThat(a.areaName())
                    .as("会場は未定でも東京であることは分かる（ADR-0022）")
                    .isEqualTo("東京");
            assertThat(a.performanceStartTime()).isNull();
            assertThat(a.performanceEndTime()).isNull();
            assertThat(a.merchStartTime()).isNull();
            assertThat(a.merchEndTime()).isNull();
            assertThat(a.ticketUrl()).isEqualTo("https://t-dv.com/20260915_nikipre");
        }

        @Test
        @DisplayName("21.txt タイムテーブル未確定。単一会場はそのまま入れる")
        void sample21() throws IOException {
            ParsedAppearance a = only("21.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 9, 26));
            assertThat(a.eventName())
                    .isEqualTo("「アイドル甲子園 in 品川インターシティホール」supported by My-th");
            assertThat(a.venueName()).isEqualTo("東京・品川インターシティホール");
            assertThat(a.performanceStartTime()).isNull();
            assertThat(a.ticketUrl())
                    .as("🔗 の後にスペースがある。🎫 は 📝 に化けているが判定に使わない")
                    .isEqualTo("https://user.my-th.jp/tickets/event/aikou_0926");
        }

        @Test
        @DisplayName("22.txt 会場が & で並ぶ羅列。/ 以外の区切りでも会場を確定しない")
        void sample22() throws IOException {
            ParsedAppearance a = only("22.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 9, 19));
            assertThat(a.eventName()).isEqualTo("『手羽先セッション vol.19』");
            assertThat(a.venueName()).isNull();
            assertThat(a.areaName()).isEqualTo("愛知");
            assertThat(a.performanceStartTime()).isNull();
            assertThat(a.ticketUrl()).isEqualTo("https://t-dv.com/tebasession_19");
        }

        @Test
        @DisplayName("24.txt 会場が全角 ／ で並ぶサーキット。羅列を捨てて枠の会場を採る")
        void sample24() throws IOException {
            String body = sample("24.txt");
            assertThat(body)
                    .as("区切りが全角。半角 / だけを見ていた頃はサーキットと判定できず、"
                            + "羅列と枠の会場を連結した値を本番に作った")
                    .contains("東京・Veats Shibuya／SHIBUYA CLUB QUATTRO／SHIBUYA WWW X");

            ParsedAppearance a = only("24.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 7, 7));
            assertThat(a.venueName())
                    .as("枠の 📍 は 渋谷WWW X。ヘッダから都道府県だけを前置する")
                    .isEqualTo("東京・渋谷WWW X");
            assertThat(a.areaName())
                    .as("羅列でも枠の 📍 から会場を確定できたので地名は要らない")
                    .isNull();
            assertThat(a.eventName()).isEqualTo("『YORU-FES ~夜を駆けるサーキットSP~』");
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(14, 35));
            assertThat(a.merchStartTime()).isEqualTo(LocalTime.of(15, 15));
            assertThat(a.ticketUrl()).isEqualTo("http://t-dv.com/yorufes_260707");
        }

        @Test
        @DisplayName("25.txt 会場が & で並ぶ。タイムテーブルがあれば枠の会場を採る")
        void sample25() throws IOException {
            ParsedAppearance a = only("25.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 8, 5));
            assertThat(a.venueName())
                    .as("22.txt と同じ & 区切りだが、こちらはタイムテーブルがあるので確定できる")
                    .isEqualTo("愛知・NAGOYA CLUB QUATTRO");
            assertThat(a.eventName()).isEqualTo("『RAD iD LIVE-NO残業DAY SUMMER SP-』");
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(19, 55));
            assertThat(a.merchStartTime()).isEqualTo(LocalTime.of(20, 50));
        }

        @Test
        @DisplayName("26.txt 同じ日・同じ会場で 2 公演。ブロックごとに独立して解析する")
        void sample26() throws IOException {
            // 6 月公演。基準の POSTED から 30 日以上前になるため投稿日を指定する
            List<ParsedAppearance> list = extract("26.txt",
                    OffsetDateTime.of(2026, 5, 1, 12, 0, 0, 0, ZoneOffset.ofHours(9)));
            assertThat(list).hasSize(2);

            assertThat(list).allSatisfy(a -> {
                assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 6, 7));
                assertThat(a.venueName())
                        .as("枠の 📍 が無い。ヘッダ会場をそのまま使い、連結しない")
                        .isEqualTo("韓国・SETi LIVE HALL");
            });

            assertThat(list.get(0).eventName()).isEqualTo("「SETi FES vol.34」");
            assertThat(list.get(0).performanceStartTime()).isEqualTo(LocalTime.of(13, 45));
            assertThat(list.get(0).merchStartTime()).isEqualTo(LocalTime.of(15, 0));
            assertThat(list.get(0).ticketUrl()).isEqualTo("http://tiget.net/events/490635");

            assertThat(list.get(1).eventName())
                    .isEqualTo("「ジエメイ ONEMAN LIVE in KOREA」");
            assertThat(list.get(1).performanceStartTime()).isEqualTo(LocalTime.of(19, 20));
            assertThat(list.get(1).merchStartTime()).isEqualTo(LocalTime.of(21, 0));
            assertThat(list.get(1).ticketUrl())
                    .as("ブロックごとに別のチケット URL を採る")
                    .isEqualTo("http://tiget.net/events/492193");
        }

        @Test
        @DisplayName("27.txt 日付行と 📍 行が別でも 2 公演に分かれる")
        void sample27() throws IOException {
            String body = sample("27.txt");
            assertThat(body)
                    .as("日付と 📍 が別行。同じ行にある 26.txt と構造が違う")
                    .contains("☀️6/14(日)\n📍東京・神田SQUARE HALL");
            assertThat(body)
                    .as("販売期限が公演当日。件数で数えると日付候補が 2 つに見える")
                    .contains("6/14(日)8:59まで販売");

            // 6 月公演。基準の POSTED から 30 日以上前になるため投稿日を指定する
            List<ParsedAppearance> list = extract("27.txt",
                    OffsetDateTime.of(2026, 5, 1, 12, 0, 0, 0, ZoneOffset.ofHours(9)));
            assertThat(list).hasSize(2);

            assertThat(list).allSatisfy(a ->
                    assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 6, 14)));

            ParsedAppearance first = list.get(0);
            assertThat(first.venueName()).isEqualTo("東京・神田SQUARE HALL");
            assertThat(first.eventName()).isEqualTo(
                    "「アイドル甲子園 in KANDA SQUARE HALL」supported by My-th -DAY2-");
            assertThat(first.performanceStartTime()).isEqualTo(LocalTime.of(12, 35));
            assertThat(first.performanceEndTime()).isEqualTo(LocalTime.of(13, 0));
            assertThat(first.merchStartTime()).isEqualTo(LocalTime.of(13, 20));
            assertThat(first.ticketUrl()).isEqualTo(
                    "https://user.my-th.jp/login?redirect=/tickets/event/aikou_0614");

            ParsedAppearance second = list.get(1);
            assertThat(second.venueName())
                    .as("2 公演目は別会場。ブロックが分かれていないと連結された値になる")
                    .isEqualTo("東京・渋谷WOMB LIVE");
            assertThat(second.eventName()).isEqualTo("『 IDOL STORM 』");
            assertThat(second.performanceStartTime()).isEqualTo(LocalTime.of(17, 5));
            assertThat(second.merchStartTime()).isEqualTo(LocalTime.of(17, 35));
            assertThat(second.ticketUrl()).isEqualTo("http://t-dv.com/IDOLSTORM_0614");
        }

        @Test
        @DisplayName("28.txt タイムテーブル未確定で & の羅列。会場を確定しない")
        void sample28() throws IOException {
            ParsedAppearance a = only("28.txt");
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 10, 18));
            assertThat(a.eventName()).isEqualTo("『 IDO-LIVE!! Circuit 』");
            assertThat(a.venueName()).isNull();
            assertThat(a.areaName())
                    .as("本番でこの型が UNKNOWN 色になっていた")
                    .isEqualTo("東京");
            assertThat(a.performanceStartTime()).isNull();
            assertThat(a.ticketUrl()).isEqualTo("http://eplus.jp/IDOLIVE");
        }
    }

    @Nested
    @DisplayName("抽出しない 7 件")
    class NotExtracted {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"7.txt", "8.txt", "9.txt", "10.txt", "11.txt",
                "13.txt", "23.txt"})
        @DisplayName("対象外の投稿型は Unparsed になる")
        void unparsed(String name) throws IOException {
            assertThat(parser.parse(sample(name), POSTED))
                    .isInstanceOf(ParseResult.Unparsed.class);
        }

        @Test
        @DisplayName("7.txt 次回予告つきのお礼投稿。緩い条件なら通ってしまう投稿")
        void sample7() throws IOException {
            String body = sample("7.txt");
            assertThat(body).contains("XINXIN", "📍", "8/26(水)");
            assertThat(body)
                    .as("チケット URL が無い。これから行われる公演の告知との違い")
                    .doesNotContain("🔗");
            assertThat(parser.parse(body, POSTED))
                    .isInstanceOf(ParseResult.Unparsed.class);
        }

        @Test
        @DisplayName("23.txt 出演日程解禁。📍 の行に日付が無く、販売期間の日付を拾ってしまう")
        void sample23() throws IOException {
            String body = sample("23.txt");
            assertThat(body)
                    .as("公演日は 2 日。どちらに出るかはタイムテーブルまで決まらない")
                    .contains("8/25(火) & 8/26(水)");
            assertThat(body)
                    .as("販売期間の 8/9(日) は曜日が正しく、曜日検証では落とせない")
                    .contains("8/9(日)");
            ParseResult r = parser.parse(body, POSTED);
            assertThat(((ParseResult.Unparsed) r).reason()).contains("📍 の行から公演日");
        }

        @Test
        @DisplayName("11.txt 出演時間の訂正。🎤 はあるが日付がない（docs/x-integration.md「出演時間の訂正（実サンプル 8.txt / 11.txt）」）")
        void sample11() throws IOException {
            ParseResult r = parser.parse(sample("11.txt"), POSTED);
            assertThat(((ParseResult.Unparsed) r).reason()).contains("公演日");
        }

    }
}
