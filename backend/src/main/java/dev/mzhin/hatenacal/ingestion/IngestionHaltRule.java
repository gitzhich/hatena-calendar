package dev.mzhin.hatenacal.ingestion;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 連続失敗による打ち切りの判定規則（FR-43 / NFR-09、docs/x-integration.md 第 7 章）。
 *
 * <p><b>規則をここ 1 か所に置く。</b> 実際に取り込みを止める側（{@link IngestionService}）と
 * 管理画面に警告を出す側（{@link IngestionRunQueryService}）が別々に判定すると、
 * 条件がずれても誰も気づけない。止まっているのに警告が出ない、あるいは動いているのに
 * 警告が出続ける状態になり、どちらも「連続失敗を管理者が検知できる」を満たさなくなる。
 *
 * <p><b>しきい値と、判定に使う件数をまとめて持つ。</b> 判定が「直近 N 件がすべて失敗」で
 * ある以上、DB から取る件数が N を下回ると打ち切りが永久に成立しない。
 * 両方をこのクラスが決めることで、片方だけ変えられる余地を消す。
 */
final class IngestionHaltRule {

    /**
     * これだけ連続で失敗したら打ち切る。
     *
     * <p>失敗するたびに同じ範囲を取り直すため、失敗が UTC の日跨ぎで続くと
     * 24 時間の重複排除が切れて<b>毎日再課金される</b>（FR-43）。
     */
    static final int MAX_CONSECUTIVE_FAILURES = 10;

    private IngestionHaltRule() {
    }

    /** 判定に必要な件数だけを新しい順に取るための指定。 */
    static Pageable window() {
        return PageRequest.of(0, MAX_CONSECUTIVE_FAILURES);
    }

    /**
     * 新しい順に並んだ実行記録の先頭から、失敗が連続している件数。
     *
     * <p>打ち切られると新しい実行記録が作られなくなるため、この値は
     * {@link #MAX_CONSECUTIVE_FAILURES} より大きくならない。
     *
     * @param recentDesc {@link #window()} で取った、開始日時の降順の実行記録
     */
    static int consecutiveFailures(List<IngestionRun> recentDesc) {
        int count = 0;
        for (IngestionRun run : recentDesc) {
            if (run.getStatus() != IngestionRunStatus.FAILED) {
                break;
            }
            count++;
        }
        return count;
    }

    /** 打ち切られているか。<b>自動では再開しない</b>ため、管理者が原因を確認して手で戻す。 */
    static boolean halted(List<IngestionRun> recentDesc) {
        return consecutiveFailures(recentDesc) >= MAX_CONSECUTIVE_FAILURES;
    }
}
