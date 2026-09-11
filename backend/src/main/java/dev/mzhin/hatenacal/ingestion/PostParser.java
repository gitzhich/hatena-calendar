package dev.mzhin.hatenacal.ingestion;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 投稿本文から出演情報を抽出する。仕様は docs/x-integration.md「出演情報の抽出（パース）仕様」。
 *
 * <p><b>副作用を持たない。</b> 本文と投稿日時を受け取り、抽出結果を返すだけで
 * DB も HTTP も触らない（docs/architecture.md「設計上の原則」）。
 * 実サンプルに対するテストをここに集約する。
 *
 * <p>扱うのは<b>公演告知だけ</b>（docs/x-integration.md「抽出対象の判定」）。タイムテーブルが確定していれば
 * 出演時刻まで、確定していなければ日付・会場・イベント名だけを取る。
 * それ以外は Unparsed にして管理者へ回す。
 */
@Component
public class PostParser {

    /**
     * マーカーと値の間に入りうる空白（docs/x-integration.md「実 API で見つかった表記ゆれ」）。
     *
     * <p>サンプル 13 件はすべてマーカー直後に値が続いていたが、実 API で
     * 取得した投稿は {@code 🔗 https://...} とスペースを挟んでいた。
     * <b>行内の空白だけを許す。</b>改行をまたいで許すと、別の節にある値を拾いうる。
     */
    private static final String SP = "[ \u3000\t]*";

    /** docs/x-integration.md「日付」。年は書かれないので投稿日時から補う。 */
    private static final Pattern DATE =
            Pattern.compile("(\\d{1,2})/(\\d{1,2})\\((月|火|水|木|金|土|日)(?:祝)?\\)");

    /** docs/x-integration.md「XINXIN の出演時刻」。同じ行に XINXIN を含むことを別途要求する。 */
    private static final Pattern MIC =
            Pattern.compile("🎤" + SP + "(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})");

    /** docs/x-integration.md「物販時刻」。XINXIN の語が入らないため、🎤 との位置関係で決める。 */
    private static final Pattern CAMERA =
            Pattern.compile("📸" + SP + "(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})");

    /** docs/x-integration.md「チケット URL」。🔗 マーカーの付いた URL のみを対象にする。 */
    private static final Pattern TICKET =
            Pattern.compile("🔗" + SP + "(https?://\\S+)");

    private static final String PIN = "📍";      // 📍
    private static final String MIC_MARKER = "🎤";     // 🎤
    private static final String SECTION = "▪️";  // ▪️
    /** 対応する開き括弧と閉じ括弧。実データは 3 種類が混在する（docs/x-integration.md「イベント名」）。 */
    private static final char[] OPEN_BRACKETS = {'『', '「', '｢'};
    private static final char[] CLOSE_BRACKETS = {'』', '」', '｣'};

    private static final DayOfWeek[] JP_WEEKDAYS = {
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
    };
    private static final String JP_WEEKDAY_CHARS = "月火水木金土日";

    /**
     * @param body 投稿本文。note_tweet があればそちら（docs/x-integration.md「本文の取り出し」）
     * @param postedAt 投稿日時。年の補完に使う
     */
    public ParseResult parse(String body, OffsetDateTime postedAt) {
        List<String> lines = body.lines().toList();

        // ---- docs/x-integration.md「1 投稿から複数の出演情報」パターン C：1 投稿に複数イベント ----
        // ブロックごとに独立して解析する。投稿全体を 1 イベントとして扱うと、
        // 後続ブロックの 📍 を前ブロックの「枠の会場」と誤認して連結し、
        // イベント名は前ブロックのものが使い回される（実サンプル 16.txt）
        List<Integer> starts = blockStarts(lines);
        if (starts.isEmpty()) {
            // 日付行と会場行が別になっている告知（実サンプル 6.txt / 23.txt）。
            // 境界が取れないので投稿全体を 1 ブロックとして扱う
            return parseBlock(lines, postedAt);
        }
        List<ParsedAppearance> merged = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            // 最初のブロックも境界行から始める。前置きは捨てる。
            // まとめ告知は冒頭で両方の会場を並べることがあり（実サンプル 17.txt）、
            // そこに 📍 が付くとヘッダ会場に採られる（「📍大阪・Music Club JANUSと」）。
            // ブロックが 1 つの投稿にも同じ規則を適用する。前置きの 📍 を
            // 会場に採る余地を、ブロック数によらず無くしておく
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : lines.size();
            ParseResult block = parseBlock(lines.subList(from, to), postedAt);
            if (block instanceof ParseResult.Unparsed unparsed) {
                // 判定の単位は投稿（docs/x-integration.md「1 投稿から複数の出演情報」）。1 ブロックでも成立しなければ
                // 投稿ごと管理者へ回す。取れたブロックだけ登録すると、
                // 投稿が REGISTERED になって未処理一覧に現れない
                return unparsed;
            }
            merged.addAll(((ParseResult.Extracted) block).appearances());
        }
        return new ParseResult.Extracted(List.copyOf(sorted(merged)));
    }

    /**
     * イベントブロックの開始行（docs/x-integration.md「1 投稿から複数の出演情報」パターン C）。
     *
     * <p><b>日付と 📍 を同じ行に持つ行</b>を境界にする。実サンプル 13 件では
     * この形の行は多くても 1 行しかなく、複数あるのはまとめ告知だけだった。
     * タイムテーブル内の 📍 は枠ごとの会場（ステージ名）で日付を伴わないため、
     * 6.txt のようなサーキット形式を誤って分割しない。
     */
    private static List<Integer> blockStarts(List<String> lines) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(PIN) && DATE.matcher(lines.get(i)).find()) {
                starts.add(i);
            }
        }
        return starts;
    }

    /** 1 イベント分の範囲を解析する。 */
    private ParseResult parseBlock(List<String> lines, OffsetDateTime postedAt) {
        int headerEnd = headerEnd(lines);
        List<String> header = lines.subList(0, headerEnd);

        // ---- docs/x-integration.md「抽出対象の判定」条件 2：探索範囲に日付候補があるか ----
        List<MonthDay> headerDates = performanceDatesIn(header);
        if (headerDates.isEmpty()) {
            return ParseResult.unparsed("公演日の候補が見つからない");
        }

        // ---- docs/x-integration.md「抽出対象の判定」条件 3：探索範囲に 📍 があるか ----
        int venueLine = indexOfContaining(header, PIN);
        if (venueLine < 0) {
            return ParseResult.unparsed("会場（📍）が見つからない");
        }
        String headerVenue = afterMarker(header.get(venueLine), PIN);

        // ---- docs/x-integration.md「抽出対象の判定」共通条件 3：イベント名が取れるか（docs/x-integration.md「イベント名」）----
        EventName eventName = eventNameIn(header, venueLine);
        if (eventName.value() == null) {
            return ParseResult.unparsed("イベント名が見つからない");
        }

        String ticketUrl = ticketUrlIn(lines);

        // ---- docs/x-integration.md「抽出対象の判定」：XINXIN を含む 🎤 行の有無で経路が分かれる（docs/x-integration.md「XINXIN の出演時刻」）----
        List<Integer> micLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("XINXIN") && MIC.matcher(lines.get(i)).find()) {
                micLines.add(i);
            }
        }
        if (micLines.isEmpty()) {
            if (indexOfContaining(lines, MIC_MARKER) >= 0) {
                // 🎤 行はあるが XINXIN が入っていない。タイムテーブルは公開済みで、
                // そこに XINXIN が載っていないということ。「まだ出ていない」とは
                // 別の状態であり、時刻なしの出演情報にしてはいけない
                return ParseResult.unparsed("XINXIN を含む 🎤 行がない（タイムテーブルに載っていない）");
            }
            return timetableUnknown(header, headerVenue, eventName, ticketUrl, postedAt);
        }

        List<ParsedAppearance> results = new ArrayList<>();
        for (int idx = 0; idx < micLines.size(); idx++) {
            int line = micLines.get(idx);
            int nextMic = idx + 1 < micLines.size() ? micLines.get(idx + 1) : lines.size();
            int prevMic = idx > 0 ? micLines.get(idx - 1) : -1;

            // ---- docs/x-integration.md「1 投稿から複数の出演情報」パターン B：どの日付ブロックに属するか ----
            MonthDay md = blockDateFor(lines, line, headerDates);
            if (md == null) {
                return ParseResult.unparsed("出演枠を日付に対応付けられない");
            }
            LocalDate date = resolveYear(md, postedAt);
            if (date == null) {
                return ParseResult.unparsed("告知の曜日と実際の曜日が一致しない");
            }

            Matcher m = MIC.matcher(lines.get(line));
            m.find();
            Slot slot = toSlot(m, date);
            if (slot == null) {
                return ParseResult.unparsed("出演時刻を解釈できない（日跨ぎ、または時刻の誤記）");
            }

            String venue = venueFor(lines, line, prevMic, headerEnd, headerVenue);
            TimeRange merch = merchFor(lines, line, nextMic, slot.carried());

            results.add(new ParsedAppearance(slot.date(), eventName.value(), venue,
                    slot.start(), slot.end(),
                    merch == null ? null : merch.start(),
                    merch == null ? null : merch.end(),
                    ticketUrl));
        }

        return new ParseResult.Extracted(List.copyOf(sorted(results)));
    }

    // ------------------------------------------------------------------
    // docs/x-integration.md「抽出対象の判定」経路 B：タイムテーブルが未確定の告知（ADR-0021）
    // ------------------------------------------------------------------

    /**
     * 出演時刻を持たない出演情報を 1 件だけ作る（docs/x-integration.md「抽出対象の判定」経路 B）。
     *
     * <p>公式は「公演情報解禁 / 出演日程解禁」と「タイムテーブル解禁」を分けて
     * 告知する。前者だけでも日付・会場・イベント名は確定しており、
     * <b>タイムテーブルが出るまでの間もカレンダーに載せられる</b>。
     * 後続の告知は空欄補完で時刻を埋める（docs/data-model.md「追加告知による空欄補完」）。
     *
     * <p><b>🎤 行を必須から外した分を、3 つの条件で埋め合わせる。</b>
     * 🎤 行は出演時刻の出どころであると同時に「これは公演告知だ」という証拠でもあり、
     * 外すだけでは次回予告つきのお礼投稿（実サンプル 7.txt）を弾く根拠が無くなる。
     */
    private static ParseResult timetableUnknown(List<String> header, String headerVenue,
            EventName eventName, String ticketUrl, OffsetDateTime postedAt) {
        // 条件 B-1：📍 の行から公演日がちょうど 1 つ取れる（docs/x-integration.md「日付」）。
        // この経路では ▪️ も 🎤 も無いため探索範囲が本文の末尾まで広がり、
        // 「探索範囲の全日付」というフォールバックが販売期間の日付を拾う。
        // 実サンプル 23.txt は候補が 8 個になり、うち 8/9(日) は曜日が正しいので
        // 曜日検証でも落とせない。📍 の行に限ることで、docs/x-integration.md「実際の投稿構造」の基本形
        // {M}/{D}({曜日})📍{都道府県}・{会場} そのものを条件にできる
        List<MonthDay> onPinLines = datesOnPinLines(header);
        if (onPinLines.size() != 1) {
            return ParseResult.unparsed("📍 の行から公演日を 1 つに絞れない");
        }

        // 条件 B-2：イベント名が括弧付きで取れる（docs/x-integration.md「イベント名」）。
        // 連結でのフォールバックを許すと、括弧も空行も無い投稿で本文の後半を
        // 名前にしてしまう。表示が汚れるだけでなく、event_key が後続の
        // タイムテーブル解禁と一致せず、消えない重複行になる
        if (!eventName.bracketed()) {
            return ParseResult.unparsed("イベント名が括弧で囲まれていない");
        }

        // 条件 B-3：チケット URL がある（docs/x-integration.md「チケット URL」）。
        // 「これから行われる公演の告知にはチケット情報が付き、過ぎた公演の
        // お礼投稿には付かない」という意味の違いを条件にする。🔗 は保持する項目で、
        // 捨てる値の書き方が抽出の成否を決める形にならない
        if (ticketUrl == null) {
            return ParseResult.unparsed("チケット URL（🔗）がない");
        }

        LocalDate date = resolveYear(onPinLines.get(0), postedAt);
        if (date == null) {
            return ParseResult.unparsed("告知の曜日と実際の曜日が一致しない");
        }
        return new ParseResult.Extracted(List.of(new ParsedAppearance(
                date, eventName.value(), confirmedVenue(headerVenue),
                null, null, null, null, ticketUrl)));
    }

    /**
     * 1 つに確定できる会場だけを返す。羅列なら {@code null}。
     *
     * <p>会場が並んでいる告知（実サンプル 1.txt は 7 会場、22.txt は 2 会場）では
     * <b>XINXIN がどこに出るかがタイムテーブルまで決まらない</b>。ここで羅列を
     * 入れてしまうと、値のある列は空欄補完で上書きされないため
     * （ADR-0004）、タイムテーブルが出た後も羅列が残り続ける。
     *
     * <p>区切りの判定は経路 A と共通（{@link #listsMultipleVenues}）。
     */
    private static String confirmedVenue(String headerVenue) {
        return listsMultipleVenues(headerVenue) ? null : headerVenue;
    }

    /**
     * docs/x-integration.md「1 投稿から複数の出演情報」：登録処理へ渡す順を出演開始時刻の昇順に固定する。
     *
     * <p>順序を決めないと、既存行を引き継ぐ枠が入れ替わって結果が
     * 非決定的になる（docs/data-model.md「追加告知による空欄補完」）。
     * ブロックをまたいで並べ替えるため、投稿全体の結合後にも同じ順を適用する。
     *
     * <p><b>時刻なしを先に置く。</b>タイムテーブル未確定の枠（経路 B）は開始時刻が
     * {@code null} になる。時刻ありを先に登録すると、後から来た時刻なしが
     * {@code (日付, event_key, NULL)} という別の行を作ってしまう。
     * 先に登録しておけば後続の時刻ありがその行を埋める。
     * {@code IngestionService.register} の並べ替えと同じ規則にしてある。
     */
    private static List<ParsedAppearance> sorted(List<ParsedAppearance> results) {
        results.sort(Comparator.comparing(ParsedAppearance::appearanceDate)
                .thenComparing(ParsedAppearance::performanceStartTime,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        return results;
    }

    // ------------------------------------------------------------------
    // docs/x-integration.md「日付」：探索範囲と日付
    // ------------------------------------------------------------------

    /**
     * 先頭 〜 タイムテーブルが始まる直前（docs/x-integration.md「日付」）。
     *
     * <p><b>境界は保持する項目だけで決める。</b>開演時刻（⏰ OPEN / START）は
     * 保持しない項目であり（docs/x-integration.md「実際の投稿構造」）、そこに境界を置くと
     * <b>捨てる値の書き方が抽出の成否を決めてしまう</b>。
     * 実サンプル 19.txt は ⏰ が落ちて {@code OPEN 19:00 / START 19:30} だけになっており、
     * これで探索範囲が広がって投稿ごと Unparsed になっていた。
     *
     * <p>▪️ が無い告知のために 🎤 でも切る。範囲は狭まる方向にしか動かず、
     * イベント名は必ずタイムテーブルより前にある。
     */
    private static int headerEnd(List<String> lines) {
        int section = indexOfContaining(lines, SECTION);
        if (section >= 0) {
            return section;
        }
        int mic = indexOfContaining(lines, MIC_MARKER);
        return mic >= 0 ? mic : lines.size();
    }

    private record MonthDay(int month, int day, char weekday) {
    }

    /**
     * 公演日の候補（docs/x-integration.md「日付」）。
     *
     * <p><b>📍 の行に書かれた日付を第一の手がかりにする。</b>
     * 基本形は {@code {M}/{D}({曜日})📍{都道府県}・{会場}} で日付と会場が同一行にあり
     * （docs/x-integration.md「実際の投稿構造」）、販売期間の日付が 📍 の行に載ることはない。
     *
     * <p>📍 の行に日付が無い告知（実サンプル 6.txt は日付行と会場行が別）では、
     * 探索範囲の日付をすべて候補にする。
     */
    private static List<MonthDay> performanceDatesIn(List<String> header) {
        List<MonthDay> onPinLines = datesOnPinLines(header);
        return onPinLines.isEmpty() ? monthDaysIn(header) : onPinLines;
    }

    /**
     * 📍 の行に書かれた日付だけ（docs/x-integration.md「日付」）。
     *
     * <p>フォールバックを挟まない。経路 B は探索範囲を ▪️ / 🎤 で切れず、
     * フォールバックが販売期間の日付まで拾うため、この形が要る。
     */
    private static List<MonthDay> datesOnPinLines(List<String> header) {
        return monthDaysIn(header.stream().filter(line -> line.contains(PIN)).toList());
    }

    private static List<MonthDay> monthDaysIn(List<String> lines) {
        List<MonthDay> found = new ArrayList<>();
        for (String line : lines) {
            Matcher m = DATE.matcher(line);
            while (m.find()) {
                found.add(new MonthDay(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), m.group(3).charAt(0)));
            }
        }
        return found;
    }

    /**
     * 年を補い、曜日で検証する。
     *
     * <p>手順 3 の曜日検証が安全弁になる。年の推測を誤ると曜日がずれるため、
     * 誤った年で登録される事故をここで止められる。
     */
    private static LocalDate resolveYear(MonthDay md, OffsetDateTime postedAt) {
        LocalDate postedDate = postedAt.toLocalDate();
        LocalDate candidate;
        try {
            candidate = LocalDate.of(postedDate.getYear(), md.month(), md.day());
        } catch (java.time.DateTimeException e) {
            return null; // 2/30 のような存在しない日付
        }
        // 投稿日より 30 日以上前なら翌年と解釈する。告知は未来の公演に対して行われる
        if (ChronoUnit.DAYS.between(candidate, postedDate) >= 30) {
            candidate = candidate.plusYears(1);
        }
        int i = JP_WEEKDAY_CHARS.indexOf(md.weekday());
        return candidate.getDayOfWeek() == JP_WEEKDAYS[i] ? candidate : null;
    }

    // ------------------------------------------------------------------
    // docs/x-integration.md「1 投稿から複数の出演情報」パターン B：タイムテーブル見出しによる日付の対応付け
    // ------------------------------------------------------------------

    private static MonthDay blockDateFor(List<String> lines, int micLine,
            List<MonthDay> headerDates) {
        // 直前の ▪️ 見出しを探し、その日付でヘッダの候補に対応付ける。
        // 見出しの曜日は検証しない（実サンプル 6.txt では誤記になっている）
        for (int i = micLine; i >= 0; i--) {
            if (!lines.get(i).contains(SECTION)) {
                continue;
            }
            Matcher m = DATE.matcher(lines.get(i));
            if (!m.find()) {
                break; // 見出しはあるが日付がない → 下のフォールバックへ
            }
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            return headerDates.stream()
                    .filter(d -> d.month() == month && d.day() == day)
                    .findFirst().orElse(null);
        }
        // 見出しで対応付けられない場合、ヘッダの日付が 1 つのときだけそれに紐づける
        return headerDates.size() == 1 ? headerDates.get(0) : null;
    }

    // ------------------------------------------------------------------
    // docs/x-integration.md「会場」：会場
    // ------------------------------------------------------------------

    private static String venueFor(List<String> lines, int micLine, int prevMic,
            int headerEnd, String headerVenue) {
        // 枠の 📍 は、その 🎤 行より前で最も近いもの。前の 🎤 行を越えない。
        // ヘッダにも 📍 があるため、探索はヘッダ部へ入らない位置で止める。
        // 「▪️ より後の 📍 が出演枠ごとの会場」（docs/x-integration.md「会場」）
        int lowerBound = Math.max(prevMic, headerEnd - 1);
        String slotVenue = null;
        for (int i = micLine - 1; i > lowerBound; i--) {
            if (lines.get(i).contains(PIN)) {
                slotVenue = afterMarker(lines.get(i), PIN);
                break;
            }
        }
        if (!listsMultipleVenues(headerVenue)) {
            // 単一会場。枠の 📍 はステージ名にすぎない。
            // 枠の 📍 がない告知（実サンプル 2.txt）はヘッダ会場をそのまま使う
            return slotVenue == null ? headerVenue : headerVenue + " / " + slotVenue;
        }
        if (slotVenue == null) {
            // 会場が並んでいるのに枠の 📍 が無い。どこに出るか決まらないので空欄にする。
            // 羅列を入れると空欄補完が上書きしないため、タイムテーブル解禁が来ても
            // 後から直らない（ADR-0004 / docs/x-integration.md「会場」）
            return null;
        }
        // サーキット・フェス。会場の羅列は捨て、都道府県を枠の会場に前置する
        int sep = headerVenue.indexOf('・');
        String prefecture = sep > 0 ? headerVenue.substring(0, sep) : null;
        return prefecture == null ? slotVenue : prefecture + "・" + slotVenue;
    }

    /**
     * 会場が並んでいるか（docs/x-integration.md「会場」）。
     *
     * <p>実データの区切りは 3 種類。半角 {@code /}（実サンプル 1.txt / 6.txt）、
     * {@code &}（22.txt / 25.txt）、全角 {@code ／}（24.txt）。
     *
     * <p><b>経路 A と経路 B で同じ集合を使う。</b> 以前は経路 A が {@code /} だけを
     * 見ていたため、{@code &} と全角 {@code ／} で並ぶ告知をサーキットと判定できず、
     * <b>羅列と枠の会場を連結した値を本番に作った</b>
     * （{@code 愛知・NAGOYA CLUB QUATTRO & RAD HALL / NAGOYA CLUB QUATTRO}）。
     * 判定を 2 か所に分けて持つと、片方だけ直す事故が起きる。
     */
    private static boolean listsMultipleVenues(String headerVenue) {
        return headerVenue.indexOf('/') >= 0
                || headerVenue.indexOf('／') >= 0
                || headerVenue.indexOf('&') >= 0;
    }

    // ------------------------------------------------------------------
    // docs/x-integration.md「イベント名」：イベント名
    // ------------------------------------------------------------------

    /**
     * 会場行より後、⏰ 行より前で、括弧を含む最初の行<b>全体</b>。
     *
     * <p>行全体を取るのは「lonlium pre.『LONELY KIDS』」のように主催者名が
     * 括弧の外に付くケースを取りこぼさないため。先頭のハッシュタグも除去しない
     * （「#ﾆｷﾌﾟﾚ『カンシャサイ。-秋-』」は区切りの空白がなく、
     * 除去しようとすると行全体が消える）。
     */
    /**
     * イベント名と、それを<b>括弧付きで</b>取れたか。
     *
     * <p>経路 B（docs/x-integration.md「抽出対象の判定」）は括弧付きしか受け付けないため、どちらの規則で
     * 取れたかを呼び出し側まで持ち回る必要がある。{@code value} は
     * 取れなければ {@code null}。
     */
    private record EventName(String value, boolean bracketed) {
    }

    /**
     * イベント名を取る。
     *
     * <p><b>開き括弧が見つかったら、結果が {@code null} でも連結へ落とさない。</b>
     * 落とすと「空行までに閉じなければ Unparsed」（{@link #bracketedName}）が
     * 効かなくなり、閉じ括弧の無い行の後ろが連結されてしまう。
     */
    private static EventName eventNameIn(List<String> header, int venueLine) {
        for (int i = venueLine + 1; i < header.size(); i++) {
            int kind = openBracketKind(header.get(i));
            if (kind >= 0) {
                return new EventName(bracketedName(header, i, kind), true);
            }
        }
        return new EventName(joinedEventName(header, venueLine), false);
    }

    /** 行に最初に現れる開き括弧の種類。無ければ -1。 */
    private static int openBracketKind(String line) {
        int found = -1;
        int at = Integer.MAX_VALUE;
        for (int k = 0; k < OPEN_BRACKETS.length; k++) {
            int i = line.indexOf(OPEN_BRACKETS[k]);
            if (i >= 0 && i < at) {
                at = i;
                found = k;
            }
        }
        return found;
    }

    /**
     * 開き括弧の行から始まるイベント名。
     *
     * <p><b>同じ行で閉じていなければ、閉じるまで後続行を連結する。</b>
     * 開き括弧の行だけを採ると {@code 『テストイベント} のように
     * <b>閉じ括弧の無い値</b>ができ、そのまま公開される。
     *
     * <p>空行までに閉じなければ {@code null} を返し、投稿ごと Unparsed にする。
     * 壊れた名前を登録するより取りこぼす（docs/x-integration.md「1 投稿から複数の出演情報」と同じ判断）。
     */
    private static String bracketedName(List<String> header, int line, int kind) {
        char open = OPEN_BRACKETS[kind];
        char close = CLOSE_BRACKETS[kind];
        String first = header.get(line);
        if (first.indexOf(close, first.indexOf(open) + 1) >= 0) {
            return first.trim();
        }
        StringBuilder joined = new StringBuilder(first.trim());
        for (int i = line + 1; i < header.size(); i++) {
            String next = header.get(i).trim();
            if (next.isEmpty()) {
                return null;
            }
            joined.append(' ').append(next);
            if (next.indexOf(close) >= 0) {
                return joined.toString();
            }
        }
        return null;
    }

    /**
     * 括弧を持たないイベント名（実サンプル 20.txt）。
     *
     * <p>会場行の次から<b>空行まで</b>を 1 行にまとめる。20.txt は
     * {@code HATENA CREATION Presents} / {@code ジエメイ VS XINXIN} /
     * {@code BANDSET 2MAN LIVE} の 3 行に分かれており、括弧が 1 つも無い。
     *
     * <p><b>括弧がある場合はこの経路に来ない。</b>括弧があるときに前の行まで
     * まとめると、5.txt の住所行（{@code 千葉県佐倉市飯野820}）や
     * 6.txt の会場数（{@code 他 全12会場}）まで巻き込む。
     * まとめてよいのは「括弧が無く、他に手がかりが無い」ときだけである。
     */
    private static String joinedEventName(List<String> header, int venueLine) {
        StringBuilder joined = new StringBuilder();
        for (int i = venueLine + 1; i < header.size(); i++) {
            String line = header.get(i).trim();
            if (line.isEmpty()) {
                break;
            }
            if (!joined.isEmpty()) {
                joined.append(' ');
            }
            joined.append(line);
        }
        return joined.isEmpty() ? null : joined.toString();
    }

    // ------------------------------------------------------------------
    // 第 5.6 / 5.7 節：時刻
    // ------------------------------------------------------------------

    private record TimeRange(LocalTime start, LocalTime end) {
    }

    /** 深夜公演で日付を繰り上げたかどうかを持つ（ADR-0011）。 */
    private record Slot(LocalDate date, LocalTime start, LocalTime end, boolean carried) {
    }

    private static Slot toSlot(Matcher m, LocalDate date) {
        int sh = Integer.parseInt(m.group(1));
        int sm = Integer.parseInt(m.group(2));
        int eh = Integer.parseInt(m.group(3));
        int em = Integer.parseInt(m.group(4));
        if (sm > 59 || em > 59 || sh >= 30 || eh >= 30) {
            return null; // 正規表現の \d{1,2} は 2 桁を素通しするので値域を別途見る
        }
        boolean startCarried = sh >= 24;
        boolean endCarried = eh >= 24;
        if (startCarried != endCarried) {
            // 出演枠そのものが日を跨ぐ（🎤23:50-24:30）。どちらの暦日に置いても
            // 片方の時刻を表現できず、時刻の順序制約にも反する
            return null;
        }
        LocalTime start = LocalTime.of(startCarried ? sh - 24 : sh, sm);
        LocalTime end = LocalTime.of(endCarried ? eh - 24 : eh, em);
        if (start.isAfter(end)) {
            return null;
        }
        return new Slot(startCarried ? date.plusDays(1) : date, start, end, startCarried);
    }

    /**
     * その枠の物販時刻。🎤 行より後の最初の 📸 行で、<b>次の 🎤 行を越えない</b>。
     *
     * <p>取れないことを許容する側に倒す。物販時刻は補助情報であり、
     * ここで投稿全体を Unparsed にはしない。
     */
    private static TimeRange merchFor(List<String> lines, int micLine, int nextMic,
            boolean performanceCarried) {
        for (int i = micLine + 1; i < nextMic; i++) {
            Matcher m = CAMERA.matcher(lines.get(i));
            if (!m.find()) {
                continue;
            }
            int sh = Integer.parseInt(m.group(1));
            int sm = Integer.parseInt(m.group(2));
            int eh = Integer.parseInt(m.group(3));
            int em = Integer.parseInt(m.group(4));
            if (sm > 59 || em > 59 || sh >= 30 || eh >= 30) {
                return null;
            }
            boolean startCarried = sh >= 24;
            boolean endCarried = eh >= 24;
            // 繰り上げの判定が出演時刻と一致しないときは保存しない。
            // 行の暦日は出演時刻で決まるため、食い違いを行の中に持ち込まない
            // （docs/data-model.md「タイムゾーンの扱い」）
            if (startCarried != endCarried || startCarried != performanceCarried) {
                return null;
            }
            LocalTime start = LocalTime.of(startCarried ? sh - 24 : sh, sm);
            LocalTime end = LocalTime.of(endCarried ? eh - 24 : eh, em);
            return start.isAfter(end) ? null : new TimeRange(start, end);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // docs/x-integration.md「チケット URL」：チケット URL
    // ------------------------------------------------------------------

    private static String ticketUrlIn(List<String> lines) {
        for (String line : lines) {
            Matcher m = TICKET.matcher(line);
            if (m.find()) {
                String url = m.group(1);
                // 取得した URL は信頼しない入力として扱う（NFR-03 / T-03 の第 1 層）
                return url.startsWith("http://") || url.startsWith("https://") ? url : null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------

    private static int indexOfContaining(List<String> lines, String needle) {
        return Optional.of(lines).map(ls -> {
            for (int i = 0; i < ls.size(); i++) {
                if (ls.get(i).contains(needle)) {
                    return i;
                }
            }
            return -1;
        }).orElse(-1);
    }

    private static String afterMarker(String line, String marker) {
        return line.substring(line.indexOf(marker) + marker.length()).trim();
    }
}
