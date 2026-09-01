package dev.mzhin.hatenacal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ログイン試行のレート制限（FR-20 / T-02）。 */
class LoginAttemptLimiterTest {

    /** 進められる時計。窓の経過をテストするため。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    @Test
    @DisplayName("上限まで試行でき、超えると拒否される")
    void blocksAfterMaxAttempts() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_ATTEMPTS; i++) {
            assertThat(limiter.allow("1.2.3.4")).isTrue();
            limiter.recordFailure("1.2.3.4");
        }
        assertThat(limiter.allow("1.2.3.4")).isFalse();
    }

    @Test
    @DisplayName("窓が過ぎれば再び試行できる")
    void recoversAfterWindow() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        assertThat(limiter.allow("1.2.3.4")).isFalse();
        clock.advance(LoginAttemptLimiter.WINDOW.plusSeconds(1));
        assertThat(limiter.allow("1.2.3.4")).isTrue();
    }

    @Test
    @DisplayName("成功したらカウンタが消える")
    void successResetsCounter() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_ATTEMPTS - 1; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        limiter.recordSuccess("1.2.3.4");
        for (int i = 0; i < LoginAttemptLimiter.MAX_ATTEMPTS; i++) {
            assertThat(limiter.allow("1.2.3.4")).isTrue();
            limiter.recordFailure("1.2.3.4");
        }
        assertThat(limiter.allow("1.2.3.4")).isFalse();
    }

    @Test
    @DisplayName("送信元ごとに独立している")
    void countersAreIndependentPerKey() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        assertThat(limiter.allow("1.2.3.4")).isFalse();
        assertThat(limiter.allow("5.6.7.8")).isTrue();
    }
}
