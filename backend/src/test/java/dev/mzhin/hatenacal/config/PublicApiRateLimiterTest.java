package dev.mzhin.hatenacal.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mzhin.hatenacal.support.MovableClock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 公開 API の総量制限（NFR-03 / docs/security.md T-04）。
 *
 * <p>時計を固定して境界を確かめる。実時間に依存させない。
 */
class PublicApiRateLimiterTest {

    private final MovableClock clock = new MovableClock();
    private final PublicApiRateLimiter limiter = new PublicApiRateLimiter(clock);

    private void consume(int times) {
        for (int i = 0; i < times; i++) {
            limiter.allow();
        }
    }

    @Test
    @DisplayName("上限までは通す")
    void allowsUpToLimit() {
        for (int i = 1; i <= PublicApiRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            assertThat(limiter.allow()).as("%d 回目", i).isTrue();
        }
    }

    @Test
    @DisplayName("上限を超えたら弾き、超過後も弾き続ける")
    void rejectsBeyondLimit() {
        consume(PublicApiRateLimiter.MAX_REQUESTS_PER_WINDOW);
        assertThat(limiter.allow()).isFalse();
        assertThat(limiter.allow()).isFalse();
    }

    @Test
    @DisplayName("ウィンドウが明けたら数え直す")
    void resetsAfterWindow() {
        consume(PublicApiRateLimiter.MAX_REQUESTS_PER_WINDOW + 1);
        assertThat(limiter.allow()).isFalse();

        clock.advance(PublicApiRateLimiter.WINDOW);
        assertThat(limiter.allow())
                .as("ちょうど WINDOW 経過で明ける。明けないと締め出しが解除されない")
                .isTrue();
    }

    @Test
    @DisplayName("ウィンドウ内は明けない")
    void staysClosedWithinWindow() {
        consume(PublicApiRateLimiter.MAX_REQUESTS_PER_WINDOW + 1);
        clock.advance(PublicApiRateLimiter.WINDOW.minusMillis(1));
        assertThat(limiter.allow()).isFalse();
    }

    @Test
    @DisplayName("ウィンドウは最初のリクエストから始まる。連打で延長されない")
    void windowStartsAtFirstRequest() {
        limiter.allow();
        clock.advance(Duration.ofSeconds(30));
        limiter.allow();
        clock.advance(Duration.ofSeconds(30));

        // 最初から WINDOW ぶん経過したので新しいウィンドウに入る。
        // 数え直しなら上限ぶん丸ごと使えるが、連打で延長される実装だと
        // 前のウィンドウの 2 回が残り、途中で弾かれる
        for (int i = 1; i <= PublicApiRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            assertThat(limiter.allow()).as("新しいウィンドウの %d 回目", i).isTrue();
        }
        assertThat(limiter.allow()).as("使い切ったら弾く").isFalse();
    }

    @Test
    @DisplayName("上限は ISR の最悪値に対して十分な余裕がある")
    void limitHasHeadroom() {
        // 34 ページ × 2 リクエスト ÷ 5 分 ≒ 14 回/分（docs/security.md「レート制限の構成と値」）。
        // ここを下げすぎると、正常な再検証で 429 を返して公開画面が壊れる
        assertThat(PublicApiRateLimiter.MAX_REQUESTS_PER_WINDOW)
                .as("最悪値 14 回/分の 10 倍は確保する")
                .isGreaterThanOrEqualTo(140);
    }
}
