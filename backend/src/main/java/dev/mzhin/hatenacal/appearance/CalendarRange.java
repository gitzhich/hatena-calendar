package dev.mzhin.hatenacal.appearance;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * 公開カレンダーが受け付ける年月の範囲（FR-05 / ADR-0014）。
 *
 * <p>範囲を絞らないと、年月ごとに分かれるキャッシュキーを無制限に増やされ、
 * そのすべてが DB へ到達する（docs/security.md T-04）。
 * <b>この判定はデータ取得の前に行う。</b>
 */
public final class CalendarRange {

    /** イベントの暦日は JST で判定する（NFR-05）。 */
    public static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    /** サービス開始月。過去の出演情報は削除せず保持するため、下限は動かさない。 */
    public static final YearMonth SERVICE_START = YearMonth.of(2026, 1);

    /** 何か月先まで表示できるか。 */
    public static final int MAX_FUTURE_MONTHS = 24;

    /** 1 リクエストで取れる最大日数（docs/api.md 第 4.1 節）。 */
    public static final int MAX_SPAN_DAYS = 62;

    private CalendarRange() {
    }

    public static YearMonth upperBound(Clock clock) {
        return YearMonth.from(LocalDate.now(clock.withZone(JST))).plusMonths(MAX_FUTURE_MONTHS);
    }

    public static boolean contains(YearMonth month, Clock clock) {
        return !month.isBefore(SERVICE_START) && !month.isAfter(upperBound(clock));
    }

    public static boolean contains(LocalDate date, Clock clock) {
        return contains(YearMonth.from(date), clock);
    }

    /** 表示できる最初の日。 */
    public static LocalDate firstDate() {
        return SERVICE_START.atDay(1);
    }

    /** 表示できる最後の日。 */
    public static LocalDate lastDate(Clock clock) {
        return upperBound(clock).atEndOfMonth();
    }
}
