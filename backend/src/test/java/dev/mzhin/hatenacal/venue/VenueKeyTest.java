package dev.mzhin.hatenacal.venue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 会場名の正規化（docs/data-model.md「venue_key の生成規則」/ ADR-0022）。
 *
 * <p><b>実データの表記ゆれが同じ行に寄ることを固定する。</b>
 * ここが壊れると同じ会場が 2 行に割れ、地図と色を直しても片方にしか効かない。
 */
class VenueKeyTest {

    @Test
    @DisplayName("空白の有無で割れない。実データに両方の表記がある")
    void spacingCollapses() {
        assertThat(VenueKey.of("愛知・大須RADHALL"))
                .isEqualTo(VenueKey.of("愛知・大須RAD HALL"));
    }

    @Test
    @DisplayName("中黒は落ちる")
    void separatorDropped() {
        assertThat(VenueKey.of("愛知・大須RADHALL")).isEqualTo("愛知大須radhall");
    }

    @Test
    @DisplayName("英字の大小で割れない")
    void caseInsensitive() {
        assertThat(VenueKey.of("東京・渋谷DESEO"))
                .isEqualTo(VenueKey.of("東京・渋谷deseo"));
    }

    @Test
    @DisplayName("別の会場は別のキーになる")
    void differentVenues() {
        assertThat(VenueKey.of("愛知・大須RADHALL"))
                .isNotEqualTo(VenueKey.of("愛知・NAGOYA ReNY limited"));
    }

    @Test
    @DisplayName("地名が違えば別の会場として扱う")
    void placeIsPartOfTheKey() {
        assertThat(VenueKey.of("東京・SAMPLE HALL"))
                .as("同じ名前のハコが別の県にありうる")
                .isNotEqualTo(VenueKey.of("大阪・SAMPLE HALL"));
    }
}
