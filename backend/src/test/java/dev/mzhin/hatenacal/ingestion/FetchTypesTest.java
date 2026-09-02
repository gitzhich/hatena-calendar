package dev.mzhin.hatenacal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FetchTypesTest {

    private static final OffsetDateTime T =
            OffsetDateTime.of(2026, 9, 2, 2, 40, 40, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("since_id は正の値でなければならない")
    void sinceIdMustBePositive() {
        assertThatThrownBy(() -> new FetchWindow.Since(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FetchWindow.Since(-1))
                .as("0 や負値を許すと、実質的に範囲なしの取得になる")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("バックフィルの開始時刻は省略できない")
    void backfillNeedsStartTime() {
        assertThatThrownBy(() -> new FetchWindow.From(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("note_tweet が空文字なら text にフォールバックする")
    void blankNoteTweetFallsBackToText() {
        assertThat(new SourcePost(1L, "本文", "", T).body()).isEqualTo("本文");
        assertThat(new SourcePost(1L, "本文", "   ", T).body()).isEqualTo("本文");
        assertThat(new SourcePost(1L, "本文", null, T).body()).isEqualTo("本文");
    }

    @Test
    @DisplayName("取得結果の posts は不変で、外から書き換えられない")
    void resultIsImmutable() {
        List<SourcePost> mutable = new java.util.ArrayList<>();
        mutable.add(new SourcePost(1L, "a", null, T));
        FetchResult result = new FetchResult(mutable, null);

        mutable.add(new SourcePost(2L, "b", null, T));

        assertThat(result.posts())
                .as("課金の根拠になる件数が後から変わってはいけない")
                .hasSize(1);
    }

    @Test
    @DisplayName("next_token が無ければ次ページなしと判定する")
    void detectsLastPage() {
        assertThat(new FetchResult(List.of(), null).hasNextPage()).isFalse();
        assertThat(new FetchResult(List.of(), "").hasNextPage()).isFalse();
        assertThat(new FetchResult(List.of(), "tok").hasNextPage()).isTrue();
        assertThat(FetchResult.empty().resourceCount()).isZero();
    }

    @Test
    @DisplayName("設定の文字列表現に Bearer Token を出さない")
    void propertiesNeverPrintTheToken() {
        String secret = "AAAAAAAAAAAAAAAAAAAAAsecret-value";
        XApiProperties props = new XApiProperties(secret, "xinxin_official",
                "https://example.invalid", 100, 10, 3,
                Duration.ofSeconds(1), Duration.ofSeconds(60),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 3);

        assertThat(props.toString())
                .as("record の既定 toString は全フィールドを出す。"
                        + "設定オブジェクトをログに渡した瞬間にトークンが漏れる（NFR-03）")
                .doesNotContain(secret)
                .contains("<redacted>");
    }

    @Test
    @DisplayName("トークンが空なら未設定として扱う")
    void detectsMissingToken() {
        assertThat(properties(null).configured()).isFalse();
        assertThat(properties("").configured()).isFalse();
        assertThat(properties("   ").configured()).isFalse();
        assertThat(properties("token").configured()).isTrue();
    }

    private static XApiProperties properties(String token) {
        return new XApiProperties(token, "xinxin_official", "https://example.invalid",
                100, 10, 3, Duration.ofSeconds(1), Duration.ofSeconds(60),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 3);
    }
}
