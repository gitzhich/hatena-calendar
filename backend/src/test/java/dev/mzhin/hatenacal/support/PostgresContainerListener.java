package dev.mzhin.hatenacal.support;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * テスト実行のあいだだけ使う PostgreSQL を立てる。
 *
 * <p><b>開発用の DB を使わない。</b>結合テストは各テーブルを削除する。
 * {@code compose.yaml} の DB を共有していたため、{@code ./gradlew test} を
 * 走らせるたびに {@code source_account} まで消えていた。
 *
 * <p>これは黙って壊れる。行が消えたことに気づかず取り込みを動かすと、
 * {@code last_fetched_tweet_id} がテストの残した値になっており、
 * <b>取得範囲が意図せず広がって課金が跳ねる</b>。実際に、そのまま動かせば
 * 最新 1000 件を取得して $5.00（支出上限ちょうど）になる状態が起きていた。
 *
 * <p>JUnit のセッション開始時に 1 度だけ起動し、接続先をシステムプロパティで
 * 渡す。システムプロパティは環境変数より優先されるため、CI で
 * {@code DATABASE_URL} が設定されていてもこちらが勝つ
 * （docs/architecture.md 第 7 章）。
 *
 * <p><b>テストクラスには何も足さない。</b>継承や {@code @Import} を要求すると
 * 付け忘れた 1 クラスが開発用 DB を触りにいく。
 */
public class PostgresContainerListener implements LauncherSessionListener {

    /** ローカルの compose.yaml と CI に合わせる。Neon が 16/17 系のため。 */
    private static final String IMAGE = "postgres:17-alpine";

    private static PostgreSQLContainer<?> container;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (container != null) {
            return;
        }
        container = new PostgreSQLContainer<>(IMAGE);
        container.start();
        // JVM 終了時に片付ける。Testcontainers の Ryuk も後始末を行う
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop));

        System.setProperty("spring.datasource.url", container.getJdbcUrl());
        System.setProperty("spring.datasource.username", container.getUsername());
        System.setProperty("spring.datasource.password", container.getPassword());
    }
}
