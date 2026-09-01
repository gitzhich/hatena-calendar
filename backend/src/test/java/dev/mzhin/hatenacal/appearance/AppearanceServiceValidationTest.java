package dev.mzhin.hatenacal.appearance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mzhin.hatenacal.common.BadRequestException;
import dev.mzhin.hatenacal.ingestion.IngestedPostRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * 入力検証。<b>検証で弾いたときに DB を引かないこと</b>までを確かめる。
 * 範囲外の年月で DB へ到達させないことが T-04 の防御になるため
 * （docs/security.md T-04 / ADR-0014）。
 */
class AppearanceServiceValidationTest {

    private static final Clock NOW =
            Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    private final AppearanceRepository repository = mock(AppearanceRepository.class);
    private final AppearanceService service = new AppearanceService(repository,
            mock(IngestedPostRepository.class), NOW);

    @Test
    @DisplayName("from が to より後なら 400")
    void fromAfterTo() {
        assertThatThrownBy(() -> service.findForCalendar(
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("62 日ちょうどは通り、63 日は 400")
    void spanLimit() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        assertThatCode(() -> service.findForCalendar(from, from.plusDays(61)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.findForCalendar(from, from.plusDays(62)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("範囲外の年月は DB を引かずに 400。ここが T-04 の防御線")
    void outOfRangeDoesNotTouchDatabase() {
        assertThatThrownBy(() -> service.findForCalendar(
                LocalDate.of(9999, 12, 1), LocalDate.of(9999, 12, 31)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.findForCalendar(
                LocalDate.of(1000, 1, 1), LocalDate.of(1000, 1, 31)))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).findForCalendar(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("範囲内なら DB を引く")
    void inRangeQueriesDatabase() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        org.mockito.Mockito.when(repository.findForCalendar(from, to)).thenReturn(List.of());
        assertThat(service.findForCalendar(from, to)).isEmpty();
        verify(repository).findForCalendar(from, to);
    }
}
