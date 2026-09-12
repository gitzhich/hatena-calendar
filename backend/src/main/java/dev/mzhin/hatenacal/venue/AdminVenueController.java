package dev.mzhin.hatenacal.venue;

import dev.mzhin.hatenacal.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会場の管理 API（docs/api.md「会場の一覧と編集」）。認証必須。
 *
 * <p>公開 API には会場の一覧を出さない。地域と地図リンクは出演情報に載せて返す
 * （docs/api.md「期間内の出演情報一覧」）。
 */
@RestController
@RequestMapping("/api/admin/venues")
public class AdminVenueController {

    private static final int MAX_PAGE_SIZE = 100;

    private final VenueAdminService service;

    public AdminVenueController(VenueAdminService service) {
        this.service = service;
    }

    /**
     * 一覧。{@code page} / {@code size} は点検一覧と同じ丸め規則で、400 にしない。
     *
     * <p><b>{@link PageRequest} に {@code Sort} を載せない。</b> 並び順はクエリが
     * {@code ORDER BY} で決めており（{@link VenueRepository#REGION_ORDER}）、
     * ここで渡すとその後ろに追記されて意図がぶれる。
     */
    @GetMapping
    public PageResponse<AdminVenueDto> list(
            @RequestParam(defaultValue = "false") boolean unresolved,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int capped = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return service.list(unresolved, PageRequest.of(Math.max(page, 0), capped));
    }

    /** 1 件。編集画面が現在値を読む口。存在しなければ 404。 */
    @GetMapping("/{id}")
    public AdminVenueDto get(@PathVariable Long id) {
        return service.find(id);
    }

    /** 訂正。<b>更新すると自動処理がこの行を上書きしなくなる。</b> */
    @PutMapping("/{id}")
    public AdminVenueDto update(@PathVariable Long id, @Valid @RequestBody VenueCommand cmd) {
        return service.update(id, cmd);
    }

    /**
     * 削除。<b>出演情報から参照されている会場は 409。</b>
     *
     * <p>会場名の書き換えで取り残された行を消すための操作。参照されている会場を
     * 消せるようにすると、公開ページから地域と地図リンクが落ちる。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 再試行の間隔を待たずに解決する。管理者が編集済みの会場には 409 を返す。 */
    @PostMapping("/{id}/resolve-place-id")
    public AdminVenueDto resolvePlaceId(@PathVariable Long id) {
        return service.resolvePlaceId(id);
    }
}
