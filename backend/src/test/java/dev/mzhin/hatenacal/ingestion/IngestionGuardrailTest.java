package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 課金の暴走を防ぐ規約の検査（docs/x-integration.md 第 9 章）。
 *
 * <p><b>ここは振る舞いのテストではない。</b>「全件取り直しの経路が存在しない」
 * ことも「テストが実 API を叩かない」ことも、動かして確かめると
 * <b>確かめること自体が課金される</b>。だからソースと型を見る。
 *
 * <p>守らせたいのは次の 3 つ。いずれも破れたときの損害が大きい。
 *
 * <ul>
 *   <li>範囲を持たない取得ができてしまう（24 時間の重複排除が外れて再課金）
 *   <li>テストが実 API を叩く（CI が回るたびに課金）
 *   <li>取得位置が後退する（同じ範囲を取り直して再課金）
 * </ul>
 */
class IngestionGuardrailTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path TEST = Path.of("src/test/java");

    @Test
    @DisplayName("取得範囲は sealed で、範囲なしの取得を表現できない")
    void fetchWindowIsSealedAndBounded() {
        assertThat(FetchWindow.class.isSealed())
                .as("sealed を外すと範囲なしの実装を足せてしまう（FR-40）")
                .isTrue();

        assertThat(FetchWindow.class.getPermittedSubclasses())
                .as("取得範囲は「差分」か「開始時刻つきバックフィル」のみ。"
                        + "範囲を持たない第 3 の形を足さない")
                .containsExactlyInAnyOrder(FetchWindow.Since.class, FetchWindow.From.class);
    }

    @Test
    @DisplayName("取得の入口は必ず取得範囲を要求する")
    void fetchEntryPointRequiresWindow() throws Exception {
        List<java.lang.reflect.Method> methods =
                Stream.of(XApiClient.class.getDeclaredMethods()).toList();

        assertThat(methods)
                .as("引数の少ないオーバーロードを足すと、範囲なしの呼び出しが書けてしまう")
                .hasSize(1);
        assertThat(methods.get(0).getParameterTypes())
                .as("取得範囲は省略できない引数であること")
                .contains(FetchWindow.class);
    }

    @Test
    @DisplayName("タイムラインの URL を組み立てるのは XApiHttpClient だけ")
    void onlyOneCallSiteBuildsTheTimelineUrl() throws IOException {
        List<Path> offenders = javaFiles(MAIN)
                .filter(p -> !p.endsWith("XApiHttpClient.java"))
                .filter(p -> readSource(p).contains("/tweets"))
                .toList();

        assertThat(offenders)
                .as("取得を別経路で書かれると、since_id の保証が効かなくなる")
                .isEmpty();
    }

    @Test
    @DisplayName("テストコードが実 X API を叩かない")
    void testsNeverCallTheRealApi() throws IOException {
        List<Path> offenders = javaFiles(TEST)
                .filter(p -> !p.endsWith("IngestionGuardrailTest.java"))
                .filter(p -> {
                    String src = readSource(p);
                    return src.contains("api.x.com") || src.contains("api.twitter.com");
                })
                .toList();

        assertThat(offenders)
                .as("実 API は呼ぶたびに課金される。CI が回るたびに請求が増える（ADR-0002）")
                .isEmpty();
    }

    @Test
    @DisplayName("取得位置の更新は前進するときだけに絞られている")
    void fetchPositionOnlyMovesForward() throws IOException {
        String source = readSource(MAIN.resolve(
                "dev/mzhin/hatenacal/ingestion/SourceAccountRepository.java"));

        assertThat(stripComments(source))
                .as("無条件の UPDATE だと後退しうる。後退すると翌日に同じ投稿を"
                        + "取り直して再課金する（docs/x-integration.md 第 2.2 節）")
                .contains("lastFetchedTweetId <")
                .contains("lastFetchedTweetId IS NULL");
    }

    private static Stream<Path> javaFiles(Path root) throws IOException {
        return Files.walk(root).filter(p -> p.toString().endsWith(".java"));
    }

    private static String readSource(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("ソースを読めません: " + path, e);
        }
    }

    /** 説明文に禁止語を書けるよう、コメントを落としてから見る。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
