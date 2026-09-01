package dev.mzhin.hatenacal.appearance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 期待値は docs/data-model.md 第 4.3.2 節の表。 */
class EventKeyTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "#ﾆｷﾌﾟﾚ『カンシャサイ。-秋-』        | ニキプレカンシャサイ秋",
        "『ORANGE CHEER』                    | orangecheer",
        "｢IGNITION-狂騒-｣                    | ignition狂騒",
        "lonlium pre.『LONELY KIDS』         | lonliumprelonelykids",
        "「くさのねアイドルフェスティバル2026」 | くさのねアイドルフェスティバル2026",
        "『DERAX JAM~ DERA MAXIMUM JAM ~』   | deraxjamderamaximumjam",
    })
    @DisplayName("実サンプルのイベント名")
    void realSamples(String eventName, String expected) {
        assertThat(EventKey.of(eventName.trim())).isEqualTo(expected.trim());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "『ORANGE CHEER』",
        "ORANGE CHEER",
        "「ORANGE　CHEER」",
        "ｏｒａｎｇｅ　ｃｈｅｅｒ",
    })
    @DisplayName("表記ゆれが同じキーに寄る。全角空白・別の括弧・全角英字")
    void absorbsVariants(String variant) {
        assertThat(EventKey.of(variant)).isEqualTo("orangecheer");
    }

    @Test
    @DisplayName("NFKC で半角カナが全角になる。全角で再告知されても同じキー")
    void halfWidthKatakanaIsNormalized() {
        assertThat(EventKey.of("ﾆｷﾌﾟﾚ")).isEqualTo(EventKey.of("ニキプレ"));
    }

    @Test
    @DisplayName("長音符を落とさない。落とすと別の語と衝突しうる")
    void keepsProlongedSoundMark() {
        assertThat(EventKey.of("『ラーメンフェス』")).isEqualTo("ラーメンフェス");
        assertThat(EventKey.of("『ラーメン』")).isNotEqualTo(EventKey.of("『ラメン』"));
    }

    @Test
    @DisplayName("切り詰めない。先頭が同じ別イベントが衝突しない")
    void doesNotTruncate() {
        assertThat(EventKey.of("LONELY KIDS")).isNotEqualTo(EventKey.of("LONELY NIGHT"));
        assertThat(EventKey.of("ORANGE CHEER")).isNotEqualTo(EventKey.of("ORANGE PARTY"));
    }

    @Test
    @DisplayName("記号だけの名前でも空にしない。event_key は NOT NULL")
    void neverReturnsEmpty() {
        assertThat(EventKey.of("！？＃")).isNotEmpty();
        assertThat(EventKey.of("---")).isNotEmpty();
    }
}
