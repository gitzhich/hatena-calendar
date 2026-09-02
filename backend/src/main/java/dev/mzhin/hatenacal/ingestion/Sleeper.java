package dev.mzhin.hatenacal.ingestion;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * バックオフの待機。テストで実時間を待たないために差し替える。
 */
public interface Sleeper {

    void sleep(Duration duration);

    @Component
    class Real implements Sleeper {
        @Override
        public void sleep(Duration duration) {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new XApiException("待機が中断されました", 0, e);
            }
        }
    }
}
