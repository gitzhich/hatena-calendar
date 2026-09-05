package dev.mzhin.hatenacal.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * ログイン試行のレート制限（FR-20 / NFR-03 / T-02）。
 *
 * <p><b>Next.js 側にも同じ制限を置くが、こちらも要る</b>（docs/api.md「内部 API」）。
 * Next.js だけだと、内部 API キーを持つ攻撃者が /internal/auth を
 * 直接叩けてしまう。
 *
 * <p>単一管理者・単一インスタンス前提のメモリ実装。Fly.io は 1 台に固定して
 * 運用するため（docs/architecture.md「取り込みジョブ」）、共有ストアを増やさない。
 * 再起動でカウンタが消えるのは許容する。
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private record Attempts(AtomicInteger count, Instant firstAt) {
    }

    private final Map<String, Attempts> byKey = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 試行してよいか。超過していれば false。 */
    public boolean allow(String key) {
        Attempts a = byKey.get(key);
        if (a == null) {
            return true;
        }
        if (Duration.between(a.firstAt(), clock.instant()).compareTo(WINDOW) > 0) {
            byKey.remove(key);
            return true;
        }
        return a.count().get() < MAX_ATTEMPTS;
    }

    public void recordFailure(String key) {
        byKey.compute(key, (k, existing) -> {
            if (existing == null
                    || Duration.between(existing.firstAt(), clock.instant()).compareTo(WINDOW) > 0) {
                return new Attempts(new AtomicInteger(1), clock.instant());
            }
            existing.count().incrementAndGet();
            return existing;
        });
    }

    /** 成功したらカウンタを捨てる。 */
    public void recordSuccess(String key) {
        byKey.remove(key);
    }
}
