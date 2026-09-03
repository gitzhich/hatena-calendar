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

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        // 残す
        "代々木公演   | 代々木公演",
        "自由ヶ丘     | 自由ヶ丘",
        "ヴィヴィッド | ヴィヴィッド",
        "AーB         | aーb",
        // 落とす
        "〆切ライブ   | 切ライブ",
        "ア・イ       | アイ",
        "波〜線       | 波線",
        "祭🎤         | 祭",
        "a b          | ab",
        "『A』「B」   | ab",
    })
    @DisplayName("保持する文字集合（docs/data-model.md 第 4.3.2 節）")
    void characterSet(String eventName, String expected) {
        /*
         * 集合を文書と実装の両方に書くと必ずずれる。ここで固定しておき、
         * 文書はこの表を写す。別の実装者が正規表現を書き直したとき、
         * 「ラーメン → ラメン」のようにキーが変わって照合が静かに壊るのを防ぐ。
         */
        assertThat(EventKey.of(eventName.trim())).isEqualTo(expected.trim());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "Ⅳ     | iv",
        "①     | 1",
        "㈱テスト | 株テスト",
        "０１２ | 012",
        "Ａｂ   | ab",
    })
    @DisplayName("NFKC は文字そのものを変える。互換文字は展開されてから除去される")
    void nfkcExpandsCompatibilityCharacters(String eventName, String expected) {
        assertThat(EventKey.of(eventName.trim())).isEqualTo(expected.trim());
    }

    @Test
    @DisplayName("記号だけの名前は原文の小文字化を返す。除去した結果ではない")
    void symbolOnlyNameFallsBackToTheOriginal() {
        /*
         * 「・」が残っているように見えるのはフォールバックであって、
         * 保持する集合に入っているからではない。event_key は NOT NULL のため、
         * 除去した結果が空なら原文を返す。
         */
        assertThat(EventKey.of("ア・イ")).as("中黒は落ちる").isEqualTo("アイ");
        assertThat(EventKey.of("・")).as("空になるので原文が返る").isEqualTo("・");
    }
}
