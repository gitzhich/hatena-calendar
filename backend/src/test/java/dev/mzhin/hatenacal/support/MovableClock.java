package dev.mzhin.hatenacal.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 手で進められる時計。
 *
 * <p>時間の経過が絡む検証（レート制限のウィンドウ、鮮度の判定）を
 * 実時間に依存させないために使う。ClockConfig が Clock をビーンにしているのは
 * このためで、テストからはこれを差し込む。
 */
public final class MovableClock extends Clock {

    private Instant at;

    public MovableClock(Instant start) {
        this.at = start;
    }

    public MovableClock() {
        this(Instant.parse("2026-09-02T00:00:00Z"));
    }

    public void advance(Duration amount) {
        at = at.plus(amount);
    }

    @Override
    public Instant instant() {
        return at;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
