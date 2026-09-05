package dev.mzhin.hatenacal.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 公開 API の総量制限（NFR-03 / docs/security.md T-04）。
 *
 * <p><b>送信元では絞らない。全体で 1 つのカウンタを持つ。</b>
 * このアプリの公開 API を叩くのは Vercel だけで、ここから見た送信元は
 * Vercel の egress IP に集約される。IP 単位で絞ると攻撃者ではなく
 * <b>全閲覧者がまとめて絞られる</b>（docs/architecture.md「公開ページのレート制限」）。
 *
 * <p>そのため<b>これは最後の防波堤であり、発動すれば閲覧者全体に影響が出る</b>。
 * 実クライアント単位の制限は Next.js 側が持つ。値はそれを踏まえて広く取る。
 *
 * <p>単一インスタンス前提のメモリ実装。Fly.io は 1 台に固定して運用するため
 * （docs/architecture.md「取り込みジョブ」）、共有ストアを増やさない。
 * 再起動でカウンタが消えるのは許容する。
 */
@Component
public class PublicApiRateLimiter {

    /**
     * 1 分あたりの上限。
     *
     * <p>ISR の再検証は 5 分間隔で、表示できる年月は約 34 か月（ADR-0014）。
     * 全ページが同時に期限切れになっても 34 ページ × 2 リクエスト ÷ 5 分
     * ≒ <b>14 回/分</b>が最悪値。20 倍の余裕を取る。
     * 根拠は docs/security.md「レート制限の構成と値」。
     */
    static final int MAX_REQUESTS_PER_WINDOW = 300;

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;

    private Instant windowStartedAt;
    private int count;

    public PublicApiRateLimiter(Clock clock) {
        this.clock = clock;
        this.windowStartedAt = Instant.MIN;
    }

    /**
     * 1 回消費して、まだ枠内かを返す。
     *
     * <p>単一のカウンタを複数のリクエストスレッドが触るため同期する。
     * 保持する状態が 2 つだけなので、ロックの粒度を細かくする意味がない。
     */
    public synchronized boolean allow() {
        Instant now = clock.instant();
        if (Duration.between(windowStartedAt, now).compareTo(WINDOW) >= 0) {
            windowStartedAt = now;
            count = 1;
            return true;
        }
        count += 1;
        return count <= MAX_REQUESTS_PER_WINDOW;
    }
}
