package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 連続失敗による打ち切りの判定規則（FR-43 / NFR-09）。
 *
 * <p>DB も HTTP も通さない。取り込みを実際に止める側と、管理画面に警告を出す側が
 * <b>同じ規則</b>を使うことがこのクラスの目的なので、規則そのものをここで固定する。
 */
class IngestionHaltRuleTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 3, 1, 0, 0, 0, ZoneOffset.UTC);

    private static IngestionRun failed() {
        IngestionRun run = IngestionRun.start(NOW);
        run.fail(NOW, "取り込みに失敗", 0);
        return run;
    }

    private static IngestionRun succeeded() {
        IngestionRun run = IngestionRun.start(NOW);
        run.succeed(NOW, 0, 0);
        return run;
    }

    /** 開始しただけで閉じていない実行（RUNNING）。 */
    private static IngestionRun running() {
        return IngestionRun.start(NOW);
    }

    /** 新しい順の実行記録。先頭が最新。 */
    private static List<IngestionRun> desc(IngestionRun... runs) {
        return List.of(runs);
    }

    private static List<IngestionRun> failures(int count) {
        List<IngestionRun> runs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            runs.add(failed());
        }
        return runs;
    }

    @Test
    @DisplayName("しきい値ちょうどで打ち切る")
    void haltsAtThreshold() {
        assertThat(IngestionHaltRule.halted(failures(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES)))
                .isTrue();
    }

    @Test
    @DisplayName("しきい値に 1 件足りなければ打ち切らない")
    void doesNotHaltJustBelowThreshold() {
        assertThat(IngestionHaltRule.halted(
                failures(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES - 1))).isFalse();
    }

    @Test
    @DisplayName("実行記録が無ければ打ち切らない。初回起動を止めない")
    void emptyHistoryDoesNotHalt() {
        assertThat(IngestionHaltRule.halted(List.of())).isFalse();
        assertThat(IngestionHaltRule.consecutiveFailures(List.of())).isZero();
    }

    @Test
    @DisplayName("最新が成功なら、その前にいくら失敗が続いていても打ち切らない")
    void recentSuccessBreaksTheStreak() {
        List<IngestionRun> runs = new ArrayList<>();
        runs.add(succeeded());
        runs.addAll(failures(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES));

        assertThat(IngestionHaltRule.consecutiveFailures(runs))
                .as("数えるのは先頭からの連続。履歴全体の失敗数ではない")
                .isZero();
        assertThat(IngestionHaltRule.halted(runs)).isFalse();
    }

    @Test
    @DisplayName("途中に成功が挟まれば、そこで連続が切れる")
    void successInTheMiddleBreaksTheStreak() {
        List<IngestionRun> runs = new ArrayList<>();
        runs.addAll(failures(3));
        runs.add(succeeded());
        runs.addAll(failures(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES));

        assertThat(IngestionHaltRule.consecutiveFailures(runs)).isEqualTo(3);
        assertThat(IngestionHaltRule.halted(runs)).isFalse();
    }

    @Test
    @DisplayName("実行中（RUNNING）は失敗として数えない")
    void runningIsNotAFailure() {
        List<IngestionRun> runs = new ArrayList<>();
        runs.add(running());
        runs.addAll(failures(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES));

        assertThat(IngestionHaltRule.consecutiveFailures(runs)).isZero();
        assertThat(IngestionHaltRule.halted(runs)).isFalse();
    }

    @Test
    @DisplayName("成功のみなら連続失敗は 0")
    void allSuccessMeansNoFailures() {
        assertThat(IngestionHaltRule.consecutiveFailures(desc(succeeded(), succeeded()))).isZero();
    }

    @Test
    @DisplayName("DB から取る件数がしきい値と一致する。下回ると打ち切りが永久に成立しない")
    void windowMatchesThreshold() {
        assertThat(IngestionHaltRule.window().getPageSize())
                .as("window() が返す件数としきい値がずれると、"
                        + "取り込みが止まらないまま失敗し続けて再課金が積み上がる")
                .isEqualTo(IngestionHaltRule.MAX_CONSECUTIVE_FAILURES);
        assertThat(IngestionHaltRule.window().getPageNumber())
                .as("最新のページを見ていること")
                .isZero();
    }
}
