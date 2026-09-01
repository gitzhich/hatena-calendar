package dev.mzhin.hatenacal.appearance;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公開 API（docs/api.md 第 4 章）。
 *
 * <p><b>更新系のメソッドをこのクラスに書かない</b>（NFR-03）。
 * 公開 API は読み取り専用であり、GET 以外を提供しない。
 */
@RestController
@RequestMapping("/api/public/appearances")
public class PublicAppearanceController {

    private final AppearanceService service;

    public PublicAppearanceController(AppearanceService service) {
        this.service = service;
    }

    /** カレンダー 1 か月分を 1 リクエストで返す（NFR-01）。日付ごとに分割しない。 */
    @GetMapping
    public PublicAppearanceListResponse list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new PublicAppearanceListResponse(service.findForCalendar(from, to));
    }
}
