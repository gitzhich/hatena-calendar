package dev.mzhin.hatenacal.appearance;

import dev.mzhin.hatenacal.common.BadRequestException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出演情報の読み取り。
 *
 * <p>認可はコントローラ層だけでなくこの層でも検証する方針だが（NFR-03）、
 * 公開 API は読み取り専用で誰でも見られるデータのため、ここでは入力検証のみ行う。
 */
@Service
public class AppearanceService {

    private final AppearanceRepository repository;
    private final Clock clock;

    public AppearanceService(AppearanceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 期間内の出演情報を返す。
     *
     * <p><b>検証を通ってから DB を引く。</b> 範囲外の年月で DB へ到達させないことが
     * T-04（無料枠の枯渇による可用性攻撃）の防御になる。
     */
    @Transactional(readOnly = true)
    public List<PublicAppearanceDto> findForCalendar(LocalDate from, LocalDate to) {
        validate(from, to);
        return repository.findForCalendar(from, to).stream().map(PublicAppearanceDto::from).toList();
    }

    private void validate(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BadRequestException("from は to 以前である必要があります");
        }
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (span > CalendarRange.MAX_SPAN_DAYS) {
            throw new BadRequestException(
                    "期間は %d 日以内である必要があります".formatted(CalendarRange.MAX_SPAN_DAYS));
        }
        // FR-05 / ADR-0014：期間の長さとは別に、月の種類数を縛る
        if (!CalendarRange.contains(from, clock) || !CalendarRange.contains(to, clock)) {
            throw new BadRequestException("表示できる期間は %s から %s までです"
                    .formatted(CalendarRange.firstDate(), CalendarRange.lastDate(clock)));
        }
    }
}
