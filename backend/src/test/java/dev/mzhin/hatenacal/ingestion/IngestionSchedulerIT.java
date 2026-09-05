package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 取り込みの停止スイッチ（docs/security.md「未決定事項」）。
 *
 * <p>トークンを消す以外の止め方を用意する。止まっていることを
 * <b>ビーンの有無で</b>確かめる。振る舞いで確かめようとすると
 * 「30 分待って何も起きない」を見ることになり、テストにならない。
 */
class IngestionSchedulerIT {

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = "X_INGESTION_ENABLED=false")
    @DisplayName("停止スイッチが有効なとき")
    class Disabled {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("スケジューラのビーンが作られない")
        void schedulerIsNotRegistered() {
            assertThat(context.getBeanNamesForType(IngestionScheduler.class))
                    .as("定期実行が止まる。取り込みの停止手段（LR-05 のサイト停止とは別系統）")
                    .isEmpty();
        }

        @Test
        @DisplayName("取り込み本体は残る。手動実行の余地を消さない")
        void serviceRemains() {
            assertThat(context.getBeanNamesForType(IngestionService.class)).isNotEmpty();
        }
    }

    @Nested
    @SpringBootTest
    @DisplayName("既定（スイッチ未指定）のとき")
    class DefaultEnabled {

        @Autowired
        private ApplicationContext context;

        @Autowired
        private IngestionService service;

        @Test
        @DisplayName("スケジューラのビーンが作られる")
        void schedulerIsRegistered() {
            assertThat(context.getBeanNamesForType(IngestionScheduler.class)).isNotEmpty();
        }

        @Test
        @DisplayName("トークン未設定なら、走っても API を叩かず何もしない")
        void doesNothingWithoutToken() {
            assertThat(service.run())
                    .as("設定漏れで無意味な呼び出しを起こさない。"
                            + "公開カレンダーは X API に依存しない（NFR-02）")
                    .isEqualTo(IngestionService.Result.NOT_CONFIGURED);
        }
    }
}
