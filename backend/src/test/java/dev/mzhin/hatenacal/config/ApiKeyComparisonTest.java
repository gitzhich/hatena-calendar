package dev.mzhin.hatenacal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API キーの比較が固定時間であることの検査（docs/api.md「認可の実装方針」）。
 *
 * <p><b>これはソースを読む検査で、振る舞いのテストではない。</b>
 * String#equals と MessageDigest#isEqual は、どちらも同じ真偽値を返す。
 * 違いは「不一致で打ち切るか」という所要時間だけで、機能テストからは
 * 観測できない。実際、比較を equals に戻す変異は他のテストをすべて素通りした。
 *
 * <p>観測できない規約は、規約のまま放置すると黙って破られる。
 * 壊れたことを検出できる形にするため、ここでソースを見る。
 */
class ApiKeyComparisonTest {

    private static final Path SOURCE =
            Path.of("src/main/java/dev/mzhin/hatenacal/config/ApiKeyFilter.java");

    @Test
    @DisplayName("MessageDigest.isEqual で比較している")
    void usesConstantTimeComparison() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertThat(source)
                .as("固定時間比較を使うこと。応答時間の差からキーを 1 文字ずつ推測されうる")
                .contains("MessageDigest.isEqual");
    }

    @Test
    @DisplayName("キーの比較に equals / == を使っていない")
    void doesNotUseEquals() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        // コメントを落としてから見る。説明文に equals と書けるようにするため
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        // provided == null は正当な null チェックなので対象にしない。
        // 見たいのは「キー同士を equals で比べていないか」
        assertThat(code)
                .as("provided.equals(...) のような比較を書かないこと")
                .doesNotContain(".equals(");
    }
}
