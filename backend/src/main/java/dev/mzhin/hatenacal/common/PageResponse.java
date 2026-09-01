package dev.mzhin.hatenacal.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** ページングの共通形（docs/api.md 第 5.1 / 5.5 / 5.7 節）。 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
