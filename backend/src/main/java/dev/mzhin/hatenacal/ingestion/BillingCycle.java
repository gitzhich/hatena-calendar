package dev.mzhin.hatenacal.ingestion;

import dev.mzhin.hatenacal.appearance.CalendarRange;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * X API の請求サイクル（NFR-04 / docs/api.md 第 5.7 節）。
 *
 * <p><b>暦月ではない。</b> クレジットの購入日を起点に切られる
 * （例: {@code Sep 2 - Oct 2}）。支出上限のリセット日と集計期間がずれていると、
 * 「想定を超えたら気づける」が成り立たない
 * （docs/runbook-x-api-setup.md 第 3.3 節）。
 *
 * <p>起点の日は設定値（{@code x.billing-cycle-start-day}）で持つ。
 * <b>1 を指定すると暦月と一致する</b>ので、暦月は特殊ケースであって別の分岐ではない。
 *
 * <p><b>境界は JST で切る</b>（NFR-05）。管理者が見る日付は JST であり、
 * UTC で切ると 9 時間分が前のサイクルに混じる。
 */
public final class BillingCycle {

    private BillingCycle() {
    }

    /**
     * 今が属する請求サイクルの開始時刻。
     *
     * @param now      現在時刻
     * @param startDay 起点の日（1〜31）。その日が無い月は月末に丸める
     */
    public static OffsetDateTime startOf(OffsetDateTime now, int startDay) {
        if (startDay < 1 || startDay > 31) {
            throw new IllegalArgumentException(
                    "請求サイクルの起点は 1〜31 です: " + startDay);
        }
        LocalDate today = now.atZoneSameInstant(CalendarRange.JST).toLocalDate();
        LocalDate candidate = clamp(today, startDay);
        // 起点がまだ来ていなければ、今のサイクルは前月に始まっている
        LocalDate start = candidate.isAfter(today) ? clamp(today.minusMonths(1), startDay)
                : candidate;
        return start.atStartOfDay(CalendarRange.JST).toOffsetDateTime();
    }

    /** 31 日起点の 2 月のように、その日が無い月は月末に丸める。 */
    private static LocalDate clamp(LocalDate month, int startDay) {
        return month.withDayOfMonth(Math.min(startDay, month.lengthOfMonth()));
    }
}
