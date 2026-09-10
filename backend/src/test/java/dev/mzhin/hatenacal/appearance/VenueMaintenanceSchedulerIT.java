package dev.mzhin.hatenacal.appearance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mzhin.hatenacal.venue.PlaceIdResolutionService;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.TestPropertySource;

/**
 * 会場の定期メンテナンスの起動設定と、2 つの段の独立性（ADR-0022）。
 *
 * <p><b>間隔を値として検査する。</b> 振る舞いで確かめようとすると
 * 「1 日待って 1 回動く」を見ることになり、テストにならない。
 *
 * <p>この間隔は設計の根拠そのものである。Neon は 1 回の実行で最低 5 分起動するため、
 * 1 日 1 回なら月 0.6 CU-hours、毎時なら約 15 CU-hours になる
 * （docs/architecture.md「運用コストの試算」）。<b>{@code P1D} を {@code PT1M} と
 * 書き間違えると、無料枠の試算が黙って崩れる。</b>
 */
class VenueMaintenanceSchedulerIT {

    /** {@code @Scheduled} の属性を解決して Duration にする。 */
    private static Duration durationOf(Environment env, String attribute) throws Exception {
        Method run = VenueMaintenanceScheduler.class.getMethod("run");
        Scheduled scheduled = run.getAnnotation(Scheduled.class);
        String expression = attribute.equals("fixedDelay")
                ? scheduled.fixedDelayString()
                : scheduled.initialDelayString();
        return Duration.parse(env.resolvePlaceholders(expression));
    }

    @Nested
    @SpringBootTest
    @DisplayName("既定の設定")
    class Defaults {

        @Autowired
        private ApplicationContext context;

        @Autowired
        private Environment env;

        @Test
        @DisplayName("スケジューラのビーンが作られる")
        void schedulerIsRegistered() {
            assertThat(context.getBeanNamesForType(VenueMaintenanceScheduler.class)).isNotEmpty();
        }

        @Test
        @DisplayName("間隔は 1 日。毎時にすると無料枠の試算が崩れる")
        void runsOncePerDay() throws Exception {
            assertThat(durationOf(env, "fixedDelay"))
                    .as("1 時間にすると月 15 CU-hours。閲覧に残る枠の 2 割強を恒久的に削る")
                    .isEqualTo(Duration.ofDays(1));
        }

        @Test
        @DisplayName("起動の少しあとに 1 回走る。初期投入を翌日まで待たせない")
        void runsShortlyAfterStartup() throws Exception {
            Duration initialDelay = durationOf(env, "initialDelay");
            assertThat(initialDelay)
                    .as("デプロイ直後に既存データの紐づけが始まる")
                    .isLessThan(Duration.ofHours(1));
            assertThat(initialDelay)
                    .as("起動と同時に走らせない。テストの文脈で発火させないため")
                    .isPositive();
        }
    }

    /**
     * 段 1（DB のみ）と段 2（Places API）の関係。
     *
     * <p><b>Spring を起動しない。</b> ここで確かめたいのは配線ではなく
     * 「片方が落ちたときに他方がどうなるか」で、DB も外部 API も要らない。
     */
    @Nested
    @DisplayName("2 つの段")
    class Stages {

        private final AppearanceService appearances = mock(AppearanceService.class);
        private final PlaceIdResolutionService placeIds = mock(PlaceIdResolutionService.class);
        private final VenueMaintenanceScheduler scheduler =
                new VenueMaintenanceScheduler(appearances, placeIds);

        @Test
        @DisplayName("段 1 が落ちても段 2 は走る")
        void linkFailureDoesNotStopResolution() {
            when(appearances.countMissingVenues()).thenThrow(new IllegalStateException("DB 障害"));

            scheduler.run();

            verify(placeIds)
                    .resolveMissing(anyInt());
        }

        @Test
        @DisplayName("段 2 が落ちても run() は例外を投げない")
        void resolutionFailureIsContained() {
            when(placeIds.resolveMissing(anyInt()))
                    .thenThrow(new IllegalStateException("Google 障害"));

            assertThatCode(scheduler::run)
                    .as("投げると fixedDelay の次回も走るが、ログが例外で埋まる")
                    .doesNotThrowAnyException();

            verify(appearances).linkMissingVenues(anyInt());
        }

        @Test
        @DisplayName("紐づけを済ませてから place_id を解決する")
        void linksBeforeResolving() {
            scheduler.run();

            InOrder order = inOrder(appearances, placeIds);
            // 先に紐づけないと、その日に作られた会場が翌日まで解決されない
            order.verify(appearances).linkMissingVenues(anyInt());
            order.verify(placeIds).resolveMissing(anyInt());
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = "venue.maintenance.enabled=false")
    @DisplayName("停止スイッチが有効なとき")
    class Disabled {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("スケジューラのビーンが作られない")
        void schedulerIsNotRegistered() {
            assertThat(context.getBeanNamesForType(VenueMaintenanceScheduler.class))
                    .as("取り込みとは別系統で止められる。片方の停止が他方を巻き込まない")
                    .isEmpty();
        }

        @Test
        @DisplayName("紐づけ本体は残る。手で呼ぶ余地を消さない")
        void serviceRemains() {
            assertThat(context.getBeanNamesForType(AppearanceService.class)).isNotEmpty();
        }
    }
}
