package dev.mzhin.hatenacal.appearance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

/** DTO の JSON 表現が JST のローカル値のまま出るか。 */
@SpringBootTest
class JsonTimeFormatTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void serializesLocalTimeWithoutShift() {
        PublicAppearanceDto dto = new PublicAppearanceDto(1L,
                LocalDate.of(2026, 9, 4), "テスト", "会場",
                dev.mzhin.hatenacal.venue.Region.CHUBU, null,
                LocalTime.of(21, 5), LocalTime.of(21, 30),
                null, null, null, "https://x.com/a/status/1");
        String json = mapper.writeValueAsString(dto);
        System.out.println("SERIALIZED >>> " + json);
        assertThat(json).contains("\"performanceStartTime\":\"21:05:00\"");
        assertThat(json).contains("\"appearanceDate\":\"2026-09-04\"");
    }
}
