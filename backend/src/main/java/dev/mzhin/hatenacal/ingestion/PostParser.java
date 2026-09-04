package dev.mzhin.hatenacal.ingestion;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 投稿本文から出演情報を抽出する。仕様は docs/x-integration.md 第 5 章。
 *
 * <p><b>副作用を持たない。</b> 本文と投稿日時を受け取り、抽出結果を返すだけで
 * DB も HTTP も触らない（docs/architecture.md 第 4.2 節）。
 * 実サンプルに対するテストをここに集約する。
 *
 * <p>扱うのは<b>タイムテーブルが確定した公演告知だけ</b>（第 5.2 節）。
 * それ以外は Unparsed にして管理者へ回す。
 */
@Component
public class PostParser {

    /**
     * マーカーと値の間に入りうる空白（第 5.12 節）。
     *
     * <p>サンプル 13 件はすべてマーカー直後に値が続いていたが、実 API で
     * 取得した投稿は {@code 🔗 https://...} とスペースを挟んでいた。
     * <b>行内の空白だけを許す。</b>改行をまたいで許すと、別の節にある値を拾いうる。
     */
    private static final String SP = "[ \u3000\t]*";

    /** 第 5.3 節。年は書かれないので投稿日時から補う。 */
    private static final Pattern DATE =
            Pattern.compile("(\\d{1,2})/(\\d{1,2})\\((月|火|水|木|金|土|日)(?:祝)?\\)");

    /** 第 5.6 節。同じ行に XINXIN を含むことを別途要求する。 */
    private static final Pattern MIC =
            Pattern.compile("🎤" + SP + "(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})");

    /** 第 5.7 節。XINXIN の語が入らないため、🎤 との位置関係で決める。 */
    private static final Pattern CAMERA =
            Pattern.compile("📸" + SP + "(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})");

    /** 第 5.8 節。🔗 マーカーの付いた URL のみを対象にする。 */
    private static final Pattern TICKET =
            Pattern.compile("🔗" + SP + "(https?://\\S+)");

    private static final String PIN = "📍";      // 📍
    private static final String CLOCK = "⏰";          // ⏰
    private static final String SECTION = "▪️";  // ▪️
    private static final char[] BRACKETS = {'『', '』', '「', '」', '｢', '｣'};

    private static final DayOfWeek[] JP_WEEKDAYS = {
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
    };
    private static final String JP_WEEKDAY_CHARS = "月火水木金土日";

    /**
     * @param body 投稿本文。note_tweet があればそちら（第 3.3 節）
     * @param postedAt 投稿日時。年の補完に使う
     */
    public ParseResult parse(String body, OffsetDateTime postedAt) {
        List<String> lines = body.lines().toList();

        // ---- 第 5.10 節 パターン C：1 投稿に複数イベント ----
        // ブロックごとに独立して解析する。投稿全体を 1 イベントとして扱うと、
        // 後続ブロックの 📍 を前ブロックの「枠の会場」と誤認して連結し、
        // イベント名は前ブロックのものが使い回される（実サンプル 16.txt）
        List<Integer> starts = blockStarts(lines);
        if (starts.size() <= 1) {
            return parseBlock(lines, postedAt);
        }
        List<ParsedAppearance> merged = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            // 最初のブロックも境界行から始める。前置きは捨てる。
            // まとめ告知は冒頭で両方の会場を並べることがあり（実サンプル 17.txt）、
            // そこに 📍 が付くとヘッダ会場に採られる（「📍大阪・Music Club JANUSと」）
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : lines.size();
            ParseResult block = parseBlock(lines.subList(from, to), postedAt);
            if (block instanceof ParseResult.Unparsed unparsed) {
                // 判定の単位は投稿（第 5.10 節）。1 ブロックでも成立しなければ
                // 投稿ごと管理者へ回す。取れたブロックだけ登録すると、
                // 投稿が REGISTERED になって未処理一覧に現れない
                return unparsed;
            }
            merged.addAll(((ParseResult.Extracted) block).appearances());
        }
        return new ParseResult.Extracted(List.copyOf(sorted(merged)));
    }

    /**
     * イベントブロックの開始行（第 5.10 節 パターン C）。
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

        // ---- 第 5.2 節 条件 2：探索範囲に日付候補があるか ----
        List<MonthDay> headerDates = monthDaysIn(header);
        if (headerDates.isEmpty()) {
            return ParseResult.unparsed("公演日の候補が見つからない");
        }

        // ---- 第 5.2 節 条件 3：探索範囲に 📍 があるか ----
        int venueLine = indexOfContaining(header, PIN);
        if (venueLine < 0) {
            return ParseResult.unparsed("会場（📍）が見つからない");
        }
        String headerVenue = afterMarker(header.get(venueLine), PIN);

        // ---- 第 5.2 節 条件 4：イベント名が取れるか（第 5.5 節）----
        String eventName = eventNameIn(header, venueLine);
        if (eventName == null) {
            return ParseResult.unparsed("イベント名が見つからない");
        }

        // ---- 第 5.2 節 条件 1：XINXIN を含む 🎤 行があるか（第 5.6 節）----
        List<Integer> micLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("XINXIN") && MIC.matcher(lines.get(i)).find()) {
                micLines.add(i);
            }
        }
        if (micLines.isEmpty()) {
            return ParseResult.unparsed("XINXIN を含む 🎤 行がない（タイムテーブル未確定）");
        }

        String ticketUrl = ticketUrlIn(lines);

        List<ParsedAppearance> results = new ArrayList<>();
        for (int idx = 0; idx < micLines.size(); idx++) {
            int line = micLines.get(idx);
            int nextMic = idx + 1 < micLines.size() ? micLines.get(idx + 1) : lines.size();
            int prevMic = idx > 0 ? micLines.get(idx - 1) : -1;

            // ---- 第 5.10 節 パターン B：どの日付ブロックに属するか ----
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

            results.add(new ParsedAppearance(slot.date(), eventName, venue,
                    slot.start(), slot.end(),
                    merch == null ? null : merch.start(),
                    merch == null ? null : merch.end(),
                    ticketUrl));
        }

        return new ParseResult.Extracted(List.copyOf(sorted(results)));
    }

    /**
     * 第 5.10 節：登録処理へ渡す順を出演開始時刻の昇順に固定する。
     *
     * <p>順序を決めないと、既存行を引き継ぐ枠が入れ替わって結果が
     * 非決定的になる（docs/data-model.md 第 7.1 節）。
     * ブロックをまたいで並べ替えるため、投稿全体の結合後にも同じ順を適用する。
     */
    private static List<ParsedAppearance> sorted(List<ParsedAppearance> results) {
        results.sort((a, b) -> {
            int c = a.appearanceDate().compareTo(b.appearanceDate());
            return c != 0 ? c : a.performanceStartTime().compareTo(b.performanceStartTime());
        });
        return results;
    }

    // ------------------------------------------------------------------
    // 第 5.3 節：探索範囲と日付
    // ------------------------------------------------------------------

    /** 先頭 〜 ⏰ で始まる最初の行の直前／なければ ▪️ を含む最初の行の直前／なければ全体。 */
    private static int headerEnd(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(CLOCK)) {
                return i;
            }
        }
        int section = indexOfContaining(lines, SECTION);
        return section >= 0 ? section : lines.size();
    }

    private record MonthDay(int month, int day, char weekday) {
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
    // 第 5.10 節 パターン B：タイムテーブル見出しによる日付の対応付け
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
    // 第 5.4 節：会場
    // ------------------------------------------------------------------

    private static String venueFor(List<String> lines, int micLine, int prevMic,
            int headerEnd, String headerVenue) {
        // 枠の 📍 は、その 🎤 行より前で最も近いもの。前の 🎤 行を越えない。
        // ヘッダにも 📍 があるため、探索はヘッダ部へ入らない位置で止める。
        // 「▪️ より後の 📍 が出演枠ごとの会場」（第 5.4 節）
        int lowerBound = Math.max(prevMic, headerEnd - 1);
        String slotVenue = null;
        for (int i = micLine - 1; i > lowerBound; i--) {
            if (lines.get(i).contains(PIN)) {
                slotVenue = afterMarker(lines.get(i), PIN);
                break;
            }
        }
        if (slotVenue == null) {
            // 枠の 📍 がない告知（実サンプル 2.txt）はヘッダ会場をそのまま使う
            return headerVenue;
        }
        if (!headerVenue.contains("/")) {
            // 単一会場。枠の 📍 はステージ名にすぎない
            return headerVenue + " / " + slotVenue;
        }
        // サーキット・フェス。会場の羅列は捨て、都道府県を枠の会場に前置する
        int sep = headerVenue.indexOf('・');
        String prefecture = sep > 0 ? headerVenue.substring(0, sep) : null;
        return prefecture == null ? slotVenue : prefecture + "・" + slotVenue;
    }

    // ------------------------------------------------------------------
    // 第 5.5 節：イベント名
    // ------------------------------------------------------------------

    /**
     * 会場行より後、⏰ 行より前で、括弧を含む最初の行<b>全体</b>。
     *
     * <p>行全体を取るのは「lonlium pre.『LONELY KIDS』」のように主催者名が
     * 括弧の外に付くケースを取りこぼさないため。先頭のハッシュタグも除去しない
     * （「#ﾆｷﾌﾟﾚ『カンシャサイ。-秋-』」は区切りの空白がなく、
     * 除去しようとすると行全体が消える）。
     */
    private static String eventNameIn(List<String> header, int venueLine) {
        for (int i = venueLine + 1; i < header.size(); i++) {
            String line = header.get(i);
            for (char b : BRACKETS) {
                if (line.indexOf(b) >= 0) {
                    return line.trim();
                }
            }
        }
        return null;
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
            // （docs/data-model.md 第 6 章）
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
    // 第 5.8 節：チケット URL
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
