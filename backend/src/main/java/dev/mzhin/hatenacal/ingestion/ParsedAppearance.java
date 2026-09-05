package dev.mzhin.hatenacal.ingestion;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 投稿から抽出した 1 件の出演情報。
 *
 * <p>event_key はここでは作らない。生成は AppearanceService の単一メソッドに
 * 集約し、自動登録・手動登録・編集のすべてがそこを通る
 * （docs/architecture.md「設計上の原則」 / ADR-0006）。
 *
 * <p>日付と時刻は<b>JST のローカル値</b>。深夜公演の繰り上げは
 * 抽出の時点で済ませてある（ADR-0011）。
 */
public record ParsedAppearance(
        LocalDate appearanceDate,
        String eventName,
        String venueName,
        LocalTime performanceStartTime,
        LocalTime performanceEndTime,
        LocalTime merchStartTime,
        LocalTime merchEndTime,
        String ticketUrl) {
}
