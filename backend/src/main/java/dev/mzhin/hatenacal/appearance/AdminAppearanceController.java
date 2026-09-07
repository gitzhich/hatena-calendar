package dev.mzhin.hatenacal.appearance;

import dev.mzhin.hatenacal.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
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
 * 管理 API（docs/api.md「管理 API」）。認証必須。
 *
 * <p>公開用と管理用でコントローラを分ける。公開側に更新系メソッドが
 * 紛れ込まないようにするため（docs/architecture.md「設計上の原則」）。
 */
@RestController
@RequestMapping("/api/admin/appearances")
public class AdminAppearanceController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AppearanceService service;

    public AdminAppearanceController(AppearanceService service) {
        this.service = service;
    }

    /** 点検一覧（FR-24）。 */
    @GetMapping
    public PageResponse<AdminAppearanceDto> list(
            @RequestParam(required = false) SourceType sourceType,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int capped = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest request = PageRequest.of(
                Math.max(page, 0), capped, AppearanceSort.from(sort).toSort());
        return PageResponse.of(service.findForReview(sourceType, request), dto -> dto);
    }

    @GetMapping("/{id}")
    public AdminAppearanceDto get(@PathVariable Long id) {
        return service.findById(id);
    }

    /** 手動登録（FR-21）。 */
    @PostMapping
    public ResponseEntity<AdminAppearanceDto> create(@Valid @RequestBody AppearanceCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(cmd));
    }

    /** 編集（FR-22）。 */
    @PutMapping("/{id}")
    public AdminAppearanceDto update(@PathVariable Long id,
            @Valid @RequestBody AppearanceCommand cmd) {
        return service.update(id, cmd);
    }

    /** 削除（FR-23）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
