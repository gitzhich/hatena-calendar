package dev.mzhin.hatenacal.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 起動ログに DB のパスワードを出さない（NFR-03 / docs/security.md「実装チェックリスト」）。
 *
 * <p><b>Hibernate は JDBC URL をマスクしない。</b>{@code org.hibernate.orm.connections.pooling}
 * の INFO で「Database info:」を出し、そこに接続文字列をそのまま載せる。
 * 認証情報は URL に含めて渡すため（docs/architecture.md「接続情報は `DATABASE_URL` 1 本で渡す」）、
 * <b>パスワードが平文で記録される</b>。同じ URL を Flyway は {@code password=********} に
 * マスクしており、ここだけ抜けていた。
 *
 * <p><b>本番の Fly のログに実際に残っていた。</b>2026-09-03 の初回デプロイ以降、
 * 起動のたびに記録されている。
 *
 * <p>塞いでいるのは {@code application.yml} の {@code logging.level} 1 行なので、
 * <b>消えても誰も気づかない</b>。次に気づくのはログを外へ出したときになる。
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class DatabaseUrlIsNotLoggedIT {

    private static final String CATEGORY = "org.hibernate.orm.connections.pooling";

    @Test
    @DisplayName("接続プールのカテゴリは INFO を出さない。出すと JDBC URL が平文で載る")
    void connectionPoolInfoIsSuppressed(CapturedOutput output) {
        // 実際に Hibernate が使うロガーへ INFO を流し、出力に届かないことを見る。
        // 設定値を読むだけだと、レベルの解釈が変わったときに気づけない
        LoggerFactory.getLogger(CATEGORY).info("MARKER_INFO_MUST_NOT_APPEAR");

        assertThat(output).doesNotContain("MARKER_INFO_MUST_NOT_APPEAR");
    }

    @Test
    @DisplayName("WARN は残す。off にすると接続プールの異常まで見えなくなる")
    void connectionPoolWarnStillReachesTheLog(CapturedOutput output) {
        Logger logger = LoggerFactory.getLogger(CATEGORY);
        logger.warn("MARKER_WARN_MUST_APPEAR");

        assertThat(output).contains("MARKER_WARN_MUST_APPEAR");
    }
}
