package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 境界ケース。docs/x-integration.md「テスト方針」「必ず書くテスト」に対応する。
 *
 * <p>実サンプルに現れないパターンは、告知の形を模した本文を組み立てて確かめる。
 * 実データの検証は PostParserSampleTest が受け持つ。
 */
class PostParserBoundaryTest {

    private final PostParser parser = new PostParser();

    private static OffsetDateTime posted(int y, int m, int d) {
        return OffsetDateTime.of(y, m, d, 12, 0, 0, 0, ZoneOffset.ofHours(9));
    }

    /** 告知の骨格を組み立てる。⏰ 行がヘッダとタイムテーブルの境界になる。 */
    private static String post(String dateLine, String timetable) {
        return """
                🔸XINXIN公演情報解禁🔸

                %s📍愛知・テスト会場
                『テストイベント』

                ⏰OPEN 17:00 / START 17:30
                🔗https://example.com/ticket

                ▪️タイムテーブル
                %s
                """.formatted(dateLine, timetable);
    }

    private ParsedAppearance only(String body, OffsetDateTime at) {
        ParseResult r = parser.parse(body, at);
        assertThat(r).isInstanceOf(ParseResult.Extracted.class);
        List<ParsedAppearance> list = ((ParseResult.Extracted) r).appearances();
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    private String reason(String body, OffsetDateTime at) {
        ParseResult r = parser.parse(body, at);
        assertThat(r).isInstanceOf(ParseResult.Unparsed.class);
        return ((ParseResult.Unparsed) r).reason();
    }

    @Nested
    @DisplayName("深夜公演（ADR-0011）")
    class Midnight {

        @Test
        @DisplayName("🎤26:00-26:30 が翌日の 02:00-02:30 になる")
        void carriesToNextDay() {
            ParsedAppearance a = only(
                    post("9/16(水)", "🎤26:00-26:30 XINXIN出演"), posted(2026, 9, 1));
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 9, 17));
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(2, 0));
            assertThat(a.performanceEndTime()).isEqualTo(LocalTime.of(2, 30));
        }

        @Test
        @DisplayName("月末の 8/31 26:00 が 9/1 02:00 になる。月が繰り上がる")
        void carriesAcrossMonth() {
            ParsedAppearance a = only(
                    post("8/31(月)", "🎤26:00-26:30 XINXIN出演"), posted(2026, 8, 1));
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(2, 0));
        }

        @Test
        @DisplayName("年末の 12/31 26:00 が翌年 1/1 02:00 になる")
        void carriesAcrossYear() {
            ParsedAppearance a = only(
                    post("12/31(木)", "🎤26:00-26:30 XINXIN出演"), posted(2026, 12, 1));
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        }

        @Test
        @DisplayName("24 時未満の通常の告知では日付が繰り上がらない")
        void doesNotCarryNormalTimes() {
            ParsedAppearance a = only(
                    post("9/16(水)", "🎤19:50-20:15 XINXIN出演"), posted(2026, 9, 1));
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 9, 16));
        }

        @Test
        @DisplayName("日を跨ぐ出演枠 🎤23:50-24:30 は Unparsed")
        void slotCrossingMidnightIsUnparsed() {
            assertThat(reason(post("9/16(水)", "🎤23:50-24:30 XINXIN出演"),
                    posted(2026, 9, 1))).contains("出演時刻");
        }

        @Test
        @DisplayName("開始だけ 24 時以上の 🎤25:00-02:00 も Unparsed")
        void carriedStartWithPlainEndIsUnparsed() {
            // 開始と終了で繰り上げの判定が食い違う形。
            // これを弾かないと 01:00-02:00 として黙って通ってしまう
            assertThat(reason(post("9/16(水)", "🎤25:00-02:00 XINXIN出演"),
                    posted(2026, 9, 1))).contains("出演時刻");
        }

        @Test
        @DisplayName("30 以上の時刻は誤記として Unparsed")
        void absurdHourIsUnparsed() {
            assertThat(reason(post("9/16(水)", "🎤31:00-31:30 XINXIN出演"),
                    posted(2026, 9, 1))).contains("出演時刻");
        }

        @Test
        @DisplayName("物販だけ 24 時以上なら物販を捨てる。出演情報自体は残す")
        void merchOnlyCarryIsDropped() {
            ParsedAppearance a = only(
                    post("9/16(水)", "🎤23:30-23:55 XINXIN出演\n📸24:10-25:00 終演後物販"),
                    posted(2026, 9, 1));
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 9, 16));
            assertThat(a.performanceStartTime()).isEqualTo(LocalTime.of(23, 30));
            assertThat(a.merchStartTime()).isNull();
        }
    }

    @Nested
    @DisplayName("年の補完と曜日検証（docs/x-integration.md「日付」）")
    class YearInference {

        @Test
        @DisplayName("曜日が一致しなければ Unparsed。年の誤りをここで止める")
        void weekdayMismatchIsUnparsed() {
            // 2026-09-16 は水曜。土と書いてあれば弾く
            assertThat(reason(post("9/16(土)", "🎤19:50-20:15 XINXIN出演"),
                    posted(2026, 9, 1))).contains("曜日");
        }

        @Test
        @DisplayName("12 月の投稿で 1 月の公演を告知したら翌年になる")
        void januaryAnnouncedInDecemberIsNextYear() {
            // 2027-01-15 は金曜
            ParsedAppearance a = only(
                    post("1/15(金)", "🎤19:50-20:15 XINXIN出演"), posted(2026, 12, 20));
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2027, 1, 15));
        }

        @Test
        @DisplayName("祝日表記 (月祝) は曜日部分だけを見る")
        void holidayNotationIsAccepted() {
            // 2026-10-12 は月曜
            ParsedAppearance a = only(
                    post("10/12(月祝)", "🎤19:50-20:15 XINXIN出演"), posted(2026, 9, 1));
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        }
    }

    @Nested
    @DisplayName("探索範囲（docs/x-integration.md「日付」）")
    class SearchScope {

        @Test
        @DisplayName("販売条件の日付を公演日にしない。📍 の行に無い日付は候補にならない")
        void salesPeriodDateIsNotPerformanceDate() {
            String body = """
                    🔸XINXIN公演情報解禁🔸

                    9/16(水)📍愛知・テスト会場
                    『テストイベント』

                    ⏰OPEN 17:00 / START 17:30
                    🔻先行チケット🔻
                    8/28(金)22:00〜8/31(月)23:59まで

                    ▪️タイムテーブル
                    🎤19:50-20:15 XINXIN出演
                    """;
            assertThat(only(body, posted(2026, 8, 1)).appearanceDate())
                    .isEqualTo(LocalDate.of(2026, 9, 16));
        }

        @Test
        @DisplayName("タイムテーブル見出しの曜日が誤記でも Unparsed にしない")
        void wrongWeekdayInSectionHeadingIsIgnored() {
            String body = """
                    🔸XINXIN公演情報解禁🔸

                    8/25(火)📍愛知・テスト会場
                    『テストイベント』

                    ⏰OPEN 13:00 / START 13:20

                    ▪️8/25(土)タイムテーブル
                    🎤16:35-17:05 XINXIN出演
                    """;
            assertThat(only(body, posted(2026, 8, 1)).appearanceDate())
                    .isEqualTo(LocalDate.of(2026, 8, 25));
        }

        @Test
        @DisplayName("⏰ が落ちていても公演日を取り違えない。境界を開演時刻に置かない")
        void missingClockMarkerDoesNotBreakDateResolution() {
            String body = """
                    🔸XINXIN公演情報解禁🔸

                    10/22(木)📍愛知・テスト会場
                    『テストイベント』

                    OPEN 19:00 / START 19:30
                    🔻先行チケット🔻
                    販売期間 : 8/21(金) 21:00〜
                    販売期間 : 8/28(金) 21:00〜

                    ▪️タイムテーブル
                    🎤20:05-20:40 XINXIN出演
                    """;
            assertThat(only(body, posted(2026, 8, 1)).appearanceDate())
                    .as("開演時刻は保持しない項目であり、その書き方で抽出の成否が変わってはならない")
                    .isEqualTo(LocalDate.of(2026, 10, 22));
        }

        @Test
        @DisplayName("▪️ が無い告知では 🎤 で切る。出演者一覧のイベント名を拾わない")
        void micLineIsTheBoundaryWithoutSectionHeading() {
            String body = """
                    🔸XINXIN公演情報解禁🔸

                    9/16(水)📍愛知・テスト会場
                    『テストイベント』

                    ⏰OPEN 17:00 / START 17:30

                    🎤19:50-20:15 XINXIN出演
                    📸21:25-22:35 終演後物販

                    【出演者(敬称略)】
                    XINXIN / 「いつかのネバーランド」
                    """;
            assertThat(only(body, posted(2026, 8, 1)).eventName())
                    .isEqualTo("『テストイベント』");
        }

        @Test
        @DisplayName("▪️ が無い告知でも、出演者一覧の括弧をイベント名に採らない")
        void performerNameIsNotTakenAsEventName() {
            String body = """
                    🔸XINXIN公演情報解禁🔸

                    9/16(水)📍愛知・テスト会場
                    括弧のないイベント名

                    ⏰OPEN 17:00 / START 17:30

                    🎤19:50-20:15 XINXIN出演
                    📸21:25-22:35 終演後物販

                    【出演者(敬称略)】
                    XINXIN / 「いつかのネバーランド」
                    """;
            assertThat(only(body, posted(2026, 8, 1)).eventName())
                    .as("🎤 で切らないと出演者一覧まで括弧を探しに行き、"
                            + "他グループ名「いつかのネバーランド」を採ってしまう")
                    .isEqualTo("括弧のないイベント名");
        }

        @Test
        @DisplayName("ブロックが 1 つでも、前置きの 📍 を会場にしない")
        void preambleVenueIsIgnoredWithSingleBlock() {
            String body = """
                    🔸明日のXINXIN公演🔸

                    📍愛知・前置き会場と📍愛知・別会場の2ステージ

                    9/16(水)📍愛知・本当の会場
                    『テストイベント』

                    ⏰OPEN 17:00 / START 17:30
                    🔗https://example.com/ticket

                    ▪️タイムテーブル
                    🎤19:50-20:15 XINXIN出演
                    """;
            assertThat(only(body, posted(2026, 8, 1)).venueName())
                    .as("解析はブロックの開始行から始める。ブロック数で規則を変えない")
                    .isEqualTo("愛知・本当の会場");
        }
    }

    @Nested
    @DisplayName("イベント名（docs/x-integration.md「イベント名」）")
    class EventName {

        /** 会場行の次にタイトルを置いた告知。⏰ 行までがヘッダになる。 */
        private static String withTitle(String titleLines) {
            return """
                    🔸XINXIN公演情報解禁🔸

                    9/16(水)📍愛知・テスト会場
                    %s

                    ⏰OPEN 17:00 / START 17:30

                    ▪️タイムテーブル
                    🎤19:50-20:15 XINXIN出演
                    """.formatted(titleLines);
        }

        @Test
        @DisplayName("括弧が 2 行にまたがるなら、閉じるまで連結する")
        void bracketSpanningTwoLinesIsJoined() {
            assertThat(only(withTitle("『テストイベント\n第2章』"), posted(2026, 8, 1))
                    .eventName())
                    .as("開き括弧の行だけを採ると『テストイベント と閉じ括弧の無い値になる")
                    .isEqualTo("『テストイベント 第2章』");
        }

        @Test
        @DisplayName("閉じた後ろに副題が続く場合も、その行まで含める")
        void textAfterClosingBracketOnTheSameLineIsKept() {
            assertThat(only(withTitle("『テスト\nイベント』-DAY1-"), posted(2026, 8, 1))
                    .eventName())
                    .isEqualTo("『テスト イベント』-DAY1-");
        }

        @Test
        @DisplayName("空行までに閉じなければ Unparsed。壊れた名前を登録しない")
        void unclosedBracketIsUnparsed() {
            assertThat(reason(withTitle("『テストイベント"), posted(2026, 8, 1)))
                    .contains("イベント名");
        }

        @Test
        @DisplayName("同じ行で閉じていれば、次の行は連結しない")
        void closedBracketDoesNotSwallowTheNextLine() {
            assertThat(only(withTitle("『テストイベント』\n-DAY1-"), posted(2026, 8, 1))
                    .eventName())
                    .as("括弧の後ろの行はタイトルの一部とは限らない（docs/x-integration.md「イベント名」）")
                    .isEqualTo("『テストイベント』");
        }

        @Test
        @DisplayName("括弧が無ければ空行までを連結する（実サンプル 20.txt）")
        void linesWithoutBracketsAreJoined() {
            assertThat(only(withTitle("テストイベント\n第2章"), posted(2026, 8, 1))
                    .eventName())
                    .isEqualTo("テストイベント 第2章");
        }
    }

    @Nested
    @DisplayName("複数枠（docs/x-integration.md「1 投稿から複数の出演情報」）")
    class MultipleSlots {

        @Test
        @DisplayName("枠は出演開始時刻の昇順で返る。順序が非決定にならない")
        void slotsAreSortedByStartTime() {
            String body = """
                    🔸XINXIN公演情報解禁🔸

                    8/25(火)📍愛知・会場A / 会場B
                    『テストイベント』

                    ⏰OPEN 13:00 / START 13:20

                    ▪️タイムテーブル
                    📍会場B
                    🎤19:50-20:15 XINXIN②
                    📍会場A
                    🎤16:35-17:05 XINXIN①
                    """;
            ParseResult r = parser.parse(body, posted(2026, 8, 1));
            List<ParsedAppearance> list = ((ParseResult.Extracted) r).appearances();
            assertThat(list).extracting(ParsedAppearance::performanceStartTime)
                    .containsExactly(LocalTime.of(16, 35), LocalTime.of(19, 50));
            assertThat(list).extracting(ParsedAppearance::venueName)
                    .containsExactly("愛知・会場A", "愛知・会場B");
        }

        @Test
        @DisplayName("他の出演者の 🎤 行は拾わない")
        void otherActsAreIgnored() {
            String body = """
                    🔸XINXIN公演情報解禁🔸

                    9/16(水)📍愛知・テスト会場
                    『テストイベント』

                    ⏰OPEN 17:00 / START 17:30

                    ▪️タイムテーブル
                    🎤18:00-18:20 別グループ出演
                    🎤19:50-20:15 XINXIN出演
                    🎤21:00-21:20 また別のグループ
                    """;
            assertThat(only(body, posted(2026, 9, 1)).performanceStartTime())
                    .isEqualTo(LocalTime.of(19, 50));
        }

        @Test
        @DisplayName("複数日開催で見出しが対応付けられなければ Unparsed")
        void ambiguousMultiDayIsUnparsed() {
            String body = """
                    🔸XINXIN公演情報解禁🔸

                    8/25(火) & 8/26(水)📍愛知・テスト会場
                    『テストイベント』

                    ⏰OPEN 13:00 / START 13:20

                    ▪️タイムテーブル
                    🎤16:35-17:05 XINXIN出演
                    """;
            assertThat(reason(body, posted(2026, 8, 1))).contains("対応付け");
        }
    }

    @Nested
    @DisplayName("1 投稿に複数イベント（docs/x-integration.md「1 投稿から複数の出演情報」パターン C）")
    class MultipleEvents {

        /** ブロックを 1 つ組み立てる。日付と 📍 が同じ行にあるので境界になる。 */
        private static String block(String date, String venue, String event, String mic) {
            return """
                    %s📍%s
                    『%s』

                    ⏰OPEN 10:00 / START 10:15
                    🔗https://example.com/%s

                    ▪️タイムテーブル
                    %s
                    """.formatted(date, venue, event, event, mic);
        }

        @Test
        @DisplayName("後のブロックが先に書かれていても、出演開始時刻の昇順で返る")
        void blocksAreSortedAcrossBlocks() {
            String body = "🔸明日のXINXIN公演🔸\n\n／\n2公演に出演‼️\n＼\n\n"
                    + block("8/25(火)", "愛知・夜会場", "夜イベント", "🎤19:50-20:15 XINXIN出演")
                    + "\nーーーーーーーーーーーーーーー\n\n"
                    + block("8/25(火)", "愛知・昼会場", "昼イベント", "🎤13:20-13:45 XINXIN出演");

            ParseResult r = parser.parse(body, posted(2026, 8, 1));
            assertThat(r).isInstanceOf(ParseResult.Extracted.class);
            List<ParsedAppearance> list = ((ParseResult.Extracted) r).appearances();

            assertThat(list).extracting(ParsedAppearance::performanceStartTime)
                    .as("ブロックをまたいでも並べ替える。順序が非決定にならない")
                    .containsExactly(LocalTime.of(13, 20), LocalTime.of(19, 50));
            assertThat(list).extracting(ParsedAppearance::eventName)
                    .containsExactly("『昼イベント』", "『夜イベント』");
            assertThat(list).extracting(ParsedAppearance::venueName)
                    .as("ブロックをまたいで会場を連結しない")
                    .containsExactly("愛知・昼会場", "愛知・夜会場");
            assertThat(list).extracting(ParsedAppearance::ticketUrl)
                    .as("チケット URL もブロックの中だけから取る")
                    .containsExactly("https://example.com/昼イベント",
                            "https://example.com/夜イベント");
        }

        @Test
        @DisplayName("1 ブロックでも成立しなければ、投稿ごと Unparsed にする")
        void oneFailingBlockDropsThePost() {
            String body = "🔸明日のXINXIN公演🔸\n\n"
                    + block("8/25(火)", "愛知・昼会場", "昼イベント", "🎤13:20-13:45 XINXIN出演")
                    + "\nーーーーーーーーーーーーーーー\n\n"
                    + block("8/25(火)", "愛知・夜会場", "夜イベント", "🎤19:50-20:15 別グループ出演");

            assertThat(reason(body, posted(2026, 8, 1)))
                    .as("取れたブロックだけ登録すると、投稿が REGISTERED になり"
                            + "未処理一覧に現れない（docs/x-integration.md「1 投稿から複数の出演情報」判定の単位は投稿）")
                    .contains("🎤");
        }
    }

    @Nested
    @DisplayName("物販時刻（docs/x-integration.md「物販時刻」）")
    class Merch {

        @Test
        @DisplayName("📸 が 🎤 より前にしかなければ物販は取らない")
        void cameraBeforeMicIsIgnored() {
            ParsedAppearance a = only(
                    post("9/16(水)", "📸17:00-18:00 物販\n🎤19:50-20:15 XINXIN出演"),
                    posted(2026, 9, 1));
            assertThat(a.merchStartTime()).isNull();
        }

        @Test
        @DisplayName("次の 🎤 行を越えて隣の枠の 📸 を拾わない")
        void doesNotCrossIntoNextSlot() {
            String body = """
                    🔸XINXIN公演情報解禁🔸

                    8/25(火)📍愛知・会場A / 会場B
                    『テストイベント』

                    ⏰OPEN 13:00 / START 13:20

                    ▪️タイムテーブル
                    📍会場A
                    🎤16:35-17:05 XINXIN①
                    📍会場B
                    🎤19:50-20:15 XINXIN②
                    📸21:25-22:45 終演後物販
                    """;
            ParseResult r = parser.parse(body, posted(2026, 8, 1));
            List<ParsedAppearance> list = ((ParseResult.Extracted) r).appearances();
            assertThat(list.get(0).merchStartTime()).isNull();
            assertThat(list.get(1).merchStartTime()).isEqualTo(LocalTime.of(21, 25));
        }
    }

    @Nested
    @DisplayName("チケット URL（docs/x-integration.md「チケット URL」 / T-03）")
    class TicketUrl {

        @Test
        @DisplayName("🔗 の付かない URL は拾わない")
        void unmarkedUrlIsIgnored() {
            ParsedAppearance a = only("""
                    🔸XINXIN公演情報解禁🔸

                    9/16(水)📍愛知・テスト会場
                    『テストイベント』

                    ⏰OPEN 17:00 / START 17:30
                    オフィシャルサイト：https://example.com/official

                    ▪️タイムテーブル
                    🎤19:50-20:15 XINXIN出演
                    """, posted(2026, 9, 1));
            assertThat(a.ticketUrl()).isNull();
        }

        @Test
        @DisplayName("javascript: スキームは通らない。T-03 の第 1 層")
        void javascriptSchemeIsRejected() {
            ParsedAppearance a = only("""
                    🔸XINXIN公演情報解禁🔸

                    9/16(水)📍愛知・テスト会場
                    『テストイベント』

                    ⏰OPEN 17:00 / START 17:30
                    🔗javascript:alert(1)

                    ▪️タイムテーブル
                    🎤19:50-20:15 XINXIN出演
                    """, posted(2026, 9, 1));
            assertThat(a.ticketUrl()).isNull();
        }
    }

    @Nested
    @DisplayName("マーカーと値の間の空白（docs/x-integration.md「実 API で見つかった表記ゆれ」）")
    class MarkerWhitespace {

        /**
         * 実 API で取得した投稿に現れた形。
         *
         * <p>サンプル 13 件はすべて {@code 🔗https://...} だったため、
         * この形は取り込みを実際に動かすまで見つからなかった。
         * <b>投稿は登録され、チケット URL だけが黙って欠ける</b>ため
         * 気づきにくい壊れ方をする。
         */
        @Test
        @DisplayName("🔗 の後にスペースがあってもチケット URL を拾う")
        void ticketUrlAfterSpace() {
            String body = """
                    🔶XINXIN東京公演タイムテーブル解禁🔶

                    9/5(土)📍東京・BLAZE GOTANDA
                    『new story』

                    ⏰OPEN 11:45 / START 12:00
                    🔗 https://t.co/8HfxMg2TkK

                    ▪️タイムテーブル
                    🎤14:30-14:55 XINXIN出演
                    📸15:15-16:35 並行物販C
                    """;
            ParsedAppearance a = only(body, posted(2026, 9, 2));

            assertThat(a.ticketUrl()).isEqualTo("https://t.co/8HfxMg2TkK");
            assertThat(a.performanceStartTime()).hasToString("14:30");
            assertThat(a.merchStartTime()).hasToString("15:15");
        }

        @Test
        @DisplayName("🎤 と 📸 の後の空白も許す")
        void timesAfterSpace() {
            ParsedAppearance a = only(
                    post("9/16(水)", "🎤 19:50-20:15 XINXIN出演\n📸 20:30-21:00 物販"),
                    posted(2026, 9, 1));

            assertThat(a.performanceStartTime()).hasToString("19:50");
            assertThat(a.merchStartTime())
                    .as("📸 を取り逃すと物販時刻が黙って欠ける")
                    .hasToString("20:30");
        }

        @Test
        @DisplayName("全角スペースも許す")
        void ideographicSpace() {
            ParsedAppearance a = only(
                    post("9/16(水)", "🎤　19:50-20:15 XINXIN出演"), posted(2026, 9, 1));

            assertThat(a.performanceStartTime()).hasToString("19:50");
        }

        @Test
        @DisplayName("改行をまたいだ値は拾わない")
        void doesNotCrossNewline() {
            String body = """
                    🔸XINXIN公演情報解禁🔸

                    9/16(水)📍愛知・テスト会場
                    『テストイベント』

                    ⏰OPEN 17:00 / START 17:30
                    🔗
                    https://example.com/別の節のURL

                    ▪️タイムテーブル
                    🎤19:50-20:15 XINXIN出演
                    """;
            assertThat(only(body, posted(2026, 9, 1)).ticketUrl())
                    .as("改行をまたいで許すと、別の節にある URL を拾いうる")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("タイムテーブル未確定の告知（docs/x-integration.md「抽出対象の判定」経路 B / ADR-0021）")
    class TimetableUnknown {

        /** 出演決定の告知。🎤 行を持たない。 */
        private String announcement(String venue, String eventName, String ticketLine) {
            return """
                    🔸XINXIN公演情報解禁🔸

                    9/15(火)📍%s
                    %s

                    ⏰OPEN 17:00 / START 17:30
                    %s

                    【出演者(敬称略)】
                    テストアクト / XINXIN
                    """.formatted(venue, eventName, ticketLine);
        }

        @Test
        @DisplayName("日付・会場・イベント名・チケットが揃えば、時刻なしで登録する")
        void extractsWithoutTimes() {
            ParsedAppearance a = only(announcement("愛知・テスト会場", "『テストイベント』",
                    "🔗https://example.com/ticket"), posted(2026, 8, 1));
            assertThat(a.appearanceDate()).isEqualTo(LocalDate.of(2026, 9, 15));
            assertThat(a.eventName()).isEqualTo("『テストイベント』");
            assertThat(a.venueName()).isEqualTo("愛知・テスト会場");
            assertThat(a.performanceStartTime()).isNull();
            assertThat(a.performanceEndTime()).isNull();
            assertThat(a.merchStartTime()).isNull();
            assertThat(a.merchEndTime()).isNull();
            assertThat(a.ticketUrl()).isEqualTo("https://example.com/ticket");
        }

        @Test
        @DisplayName("チケット URL が無ければ Unparsed。お礼投稿を弾く根拠")
        void withoutTicketUrlIsUnparsed() {
            String body = announcement("愛知・テスト会場", "『テストイベント』",
                    "🎫前売¥3,000 / 当日¥3,500");
            assertThat(reason(body, posted(2026, 8, 1)))
                    .as("これから行われる公演の告知にはチケット情報が付く")
                    .contains("チケット URL");
        }

        @Test
        @DisplayName("イベント名が括弧付きでなければ Unparsed。連結にフォールバックしない")
        void withoutBracketsIsUnparsed() {
            String body = announcement("愛知・テスト会場", "テストイベント",
                    "🔗https://example.com/ticket");
            assertThat(reason(body, posted(2026, 8, 1)))
                    .as("連結を許すと event_key が後続の告知と一致せず、重複行になる")
                    .contains("括弧");
        }

        @Test
        @DisplayName("📍 の行に日付が無ければ Unparsed。探索範囲の日付で代用しない")
        void dateOutsidePinLineIsNotUsed() {
            String body = """
                    🔸XINXIN公演出演日程解禁🔸

                    9/15(火)
                    📍愛知・テスト会場
                    『テストイベント』

                    🔗https://example.com/ticket
                    """;
            assertThat(reason(body, posted(2026, 8, 1)))
                    .as("🎤 が無い投稿は探索範囲が本文末尾まで広がり、"
                            + "販売期間の日付まで候補にしてしまう")
                    .contains("📍 の行から公演日");
        }

        @Test
        @DisplayName("📍 の行に日付が 2 つあれば Unparsed。どちらに出るか決まらない")
        void twoDatesOnPinLineIsUnparsed() {
            String body = announcement("愛知・テスト会場", "『テストイベント』",
                    "🔗https://example.com/ticket")
                    .replace("9/15(火)📍", "9/15(火) & 9/16(水)📍");
            assertThat(reason(body, posted(2026, 8, 1))).contains("📍 の行から公演日");
        }

        @Test
        @DisplayName("会場が / で並ぶなら空欄。タイムテーブルが実際の会場を埋める")
        void slashSeparatedVenueIsLeftBlank() {
            ParsedAppearance a = only(announcement("東京・会場A/会場B/会場C",
                    "『テストイベント』", "🔗https://example.com/ticket"),
                    posted(2026, 8, 1));
            assertThat(a.venueName())
                    .as("羅列を入れると、値のある列は空欄補完で上書きされず残り続ける")
                    .isNull();
        }

        @Test
        @DisplayName("会場が & で並ぶなら空欄。区切りは / だけではない")
        void ampersandSeparatedVenueIsLeftBlank() {
            ParsedAppearance a = only(announcement("愛知・会場A & 会場B",
                    "『テストイベント』", "🔗https://example.com/ticket"),
                    posted(2026, 8, 1));
            assertThat(a.venueName()).isNull();
        }

        @Test
        @DisplayName("他グループの 🎤 行があるなら登録しない。タイムテーブルは公開済み")
        void timetablePublishedWithoutXinxinIsUnparsed() {
            String body = """
                    🔸XINXIN公演情報解禁🔸

                    9/15(火)📍愛知・テスト会場
                    『テストイベント』

                    🔗https://example.com/ticket

                    ▪️タイムテーブル
                    🎤19:50-20:15 別グループ出演
                    """;
            assertThat(reason(body, posted(2026, 8, 1)))
                    .as("「まだタイムテーブルが出ていない」と「出たが XINXIN が"
                            + "載っていない」は別の状態。後者を時刻なしで登録しない")
                    .contains("🎤");
        }

        @Test
        @DisplayName("時刻なしの枠を先に返す。時刻ありが先だと別の行が作られる")
        void timelessSlotComesFirst() {
            String body = """
                    🔸XINXIN東京公演🔸

                    ☀️9/15(火)📍東京・会場A
                    『イベントA』
                    🔗https://example.com/a
                    ▪️タイムテーブル
                    🎤16:45-17:05 XINXIN出演

                    🌙9/15(火)📍東京・会場B
                    『イベントB』
                    🔗https://example.com/b
                    """;
            ParseResult r = parser.parse(body, posted(2026, 8, 1));
            assertThat(r).isInstanceOf(ParseResult.Extracted.class);
            assertThat(((ParseResult.Extracted) r).appearances())
                    .extracting(ParsedAppearance::eventName)
                    .as("時刻なしを先に登録すれば、後続の時刻ありがその行を埋める")
                    .containsExactly("『イベントB』", "『イベントA』");
        }
    }

    /**
     * ブロックの境界（docs/x-integration.md「1 投稿から複数の出演情報」）。
     *
     * <p>1 投稿に 2 公演が入る告知を、独立したブロックに割れるかを固定する。
     * 割れないと後続ブロックの 📍 を前ブロックの枠の会場と誤認する。
     */
    @Nested
    @DisplayName("ブロックの境界（docs/x-integration.md「1 投稿から複数の出演情報」）")
    class BlockBoundary {

        /** 2 公演の告知。日付行と 📍 行を分けるかを呼び出し側が決める。 */
        private String twoShows(String firstHead, String secondHead) {
            return """
                    🔸明日のXINXIN公演🔸

                    %s
                    『イベントA』

                    ⏰OPEN 9:00 / START 9:15
                    🔗https://example.com/a
                    ※販売：～9/5(土)8:59まで販売

                    ▪️タイムテーブル
                    🎤12:35-13:00 XINXIN出演

                    %s
                    『イベントB』

                    ⏰OPEN 15:00 / START 15:20
                    🔗https://example.com/b

                    ▪️タイムテーブル
                    🎤17:05-17:25 XINXIN出演
                    """.formatted(firstHead, secondHead);
        }

        private List<ParsedAppearance> extract(String body) {
            ParseResult r = parser.parse(body, posted(2026, 8, 20));
            assertThat(r).isInstanceOf(ParseResult.Extracted.class);
            return ((ParseResult.Extracted) r).appearances();
        }

        @Test
        @DisplayName("日付と 📍 が同じ行なら 2 公演に分かれる（実サンプル 26.txt）")
        void sameLineSplits() {
            List<ParsedAppearance> list = extract(twoShows(
                    "9/5(土)📍東京・A HALL", "9/5(土)📍東京・B HALL"));

            assertThat(list).hasSize(2);
            assertThat(list).extracting(ParsedAppearance::venueName)
                    .containsExactly("東京・A HALL", "東京・B HALL");
        }

        @Test
        @DisplayName("日付行と 📍 行が分かれていても 2 公演に分かれる（実サンプル 27.txt）")
        void splitLinesAlsoSplit() {
            List<ParsedAppearance> list = extract(twoShows(
                    "☀️9/5(土)\n📍東京・A HALL", "🌙9/5(土)\n📍東京・B HALL"));

            assertThat(list)
                    .as("分かれないと 2 公演目の 📍 を 1 公演目の枠の会場と誤認する")
                    .hasSize(2);
            assertThat(list).extracting(ParsedAppearance::venueName)
                    .containsExactly("東京・A HALL", "東京・B HALL");
        }

        @Test
        @DisplayName("▪️ の見出しは境界にしない。1 公演 2 枠が 2 公演に割れる")
        void sectionHeadingIsNotABoundary() {
            String body = """
                    🔸XINXIN公演タイムテーブル解禁🔸

                    9/5(土)
                    📍愛知・A HALL / B HALL
                    『イベントA』

                    ⏰OPEN 13:00 / START 13:20
                    🔗https://example.com/a

                    ▪️9/5(土)タイムテーブル
                    📍A HALL
                    🎤16:35-17:05 XINXIN①

                    📍B HALL
                    🎤19:50-20:15 XINXIN②
                    """;

            List<ParsedAppearance> list = extract(body);

            assertThat(list)
                    .as("▪️ はタイムテーブル節の見出しで、新しい公演の始まりではない")
                    .hasSize(2);
            assertThat(list).extracting(ParsedAppearance::eventName)
                    .as("同じイベントの 2 枠。ブロックが割れるとイベント名が取れなくなる")
                    .containsExactly("『イベントA』", "『イベントA』");
        }

        @Test
        @DisplayName("販売期限が公演当日でも、同じ日なら曖昧ではない")
        void duplicateHeaderDateIsNotAmbiguous() {
            String body = """
                    🔸明日のXINXIN公演🔸

                    9/5(土)
                    📍東京・A HALL
                    『イベントA』

                    ⏰OPEN 9:00 / START 9:15
                    🔗https://example.com/a
                    ※販売：～9/5(土)8:59まで販売

                    ▪️タイムテーブル
                    🎤12:35-13:00 XINXIN出演
                    """;

            assertThat(only(body, posted(2026, 8, 20)).appearanceDate())
                    .as("件数で数えると 2 つに見えるが、指しているのは同じ 1 日")
                    .isEqualTo(LocalDate.of(2026, 9, 5));
        }
    }

    /**
     * 会場の羅列（docs/x-integration.md「会場」）。
     *
     * <p><b>区切りは 3 種類。</b> 実データに半角 {@code /}（1.txt / 6.txt）、
     * {@code &}（22.txt / 25.txt）、全角 {@code ／}（24.txt）が現れる。
     * 判定を経路ごとに分けて持つと片方だけ直す事故が起きるため、同じ集合を使う。
     */
    @Nested
    @DisplayName("会場の羅列（docs/x-integration.md「会場」）")
    class VenueList {

        /** 枠の 📍 があるタイムテーブル付きの告知（経路 A）。 */
        private String withSlot(String headerVenue) {
            return """
                    🔸XINXIN公演情報解禁🔸

                    9/5(土)📍%s
                    『テストイベント』

                    ⏰OPEN 17:00 / START 17:30
                    🔗https://example.com/ticket

                    ▪️タイムテーブル
                    📍第2ステージ
                    🎤19:00-19:30 XINXIN出演
                    """.formatted(headerVenue);
        }

        /** 枠の 📍 が無いタイムテーブル付きの告知。 */
        private String withoutSlot(String headerVenue) {
            return """
                    🔸XINXIN公演情報解禁🔸

                    9/5(土)📍%s
                    『テストイベント』

                    ⏰OPEN 17:00 / START 17:30
                    🔗https://example.com/ticket

                    ▪️タイムテーブル
                    🎤19:00-19:30 XINXIN出演
                    """.formatted(headerVenue);
        }

        private String venueOf(String body) {
            return only(body, posted(2026, 8, 20)).venueName();
        }

        @Test
        @DisplayName("半角 / の羅列は、羅列を捨てて枠の会場を採る")
        void slashList() {
            assertThat(venueOf(withSlot("愛知・A HALL / B HALL")))
                    .isEqualTo("愛知・第2ステージ");
        }

        @Test
        @DisplayName("& の羅列も同じ扱い（実サンプル 25.txt）")
        void ampersandList() {
            assertThat(venueOf(withSlot("愛知・A HALL & B HALL")))
                    .as("& を見落とすと、羅列と枠の会場を連結した値ができる")
                    .isEqualTo("愛知・第2ステージ");
        }

        @Test
        @DisplayName("全角 ／ の羅列も同じ扱い（実サンプル 24.txt）")
        void fullWidthSlashList() {
            assertThat(venueOf(withSlot("愛知・A HALL／B HALL")))
                    .as("半角 / だけを見ていると全角を取り落とす")
                    .isEqualTo("愛知・第2ステージ");
        }

        @Test
        @DisplayName("羅列でなければ枠の 📍 はステージ名として連結する")
        void singleVenueConcatenatesSlot() {
            assertThat(venueOf(withSlot("愛知・テスト会場")))
                    .isEqualTo("愛知・テスト会場 / 第2ステージ");
        }

        @Test
        @DisplayName("羅列で枠の 📍 が無ければ空欄。羅列を入れると後から直らない")
        void listWithoutSlotIsBlank() {
            assertThat(venueOf(withoutSlot("愛知・A HALL & B HALL")))
                    .as("値を入れると空欄補完が上書きせず、タイムテーブル解禁が来ても"
                            + "実際の会場に直らない（ADR-0004）")
                    .isNull();
        }

        @Test
        @DisplayName("空欄にしても地名は残す。会場未定でも地域は分かる（ADR-0022）")
        void blankVenueKeepsAreaName() {
            ParsedAppearance a = only(withoutSlot("愛知・A HALL & B HALL"), posted(2026, 8, 20));
            assertThat(a.venueName()).isNull();
            assertThat(a.areaName())
                    .as("空欄にすると地域まで失われ、カレンダーの色が付かなくなる")
                    .isEqualTo("愛知");
        }

        @Test
        @DisplayName("・ が無ければ地名も取れない")
        void noSeparatorMeansNoArea() {
            ParsedAppearance a = only(withoutSlot("A HALL & B HALL"), posted(2026, 8, 20));
            assertThat(a.venueName()).isNull();
            assertThat(a.areaName()).isNull();
        }

        @Test
        @DisplayName("羅列でなく枠の 📍 も無ければヘッダ会場をそのまま使う（実サンプル 2.txt）")
        void singleVenueWithoutSlot() {
            assertThat(venueOf(withoutSlot("愛知・テスト会場")))
                    .isEqualTo("愛知・テスト会場");
        }
    }

}
