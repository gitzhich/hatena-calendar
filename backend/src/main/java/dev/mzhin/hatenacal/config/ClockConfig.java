package dev.mzhin.hatenacal.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 時刻をビーンにして、テストから固定できるようにする。
 *
 * <p>「今日」の解釈が入る箇所（FR-05 の表示範囲など）を
 * 実時刻に依存させない。日付境界のテストを書くための土台
 * （CLAUDE.md 開発上の注意）。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
