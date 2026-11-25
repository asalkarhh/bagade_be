package com.bagade.common.dto;

import org.springframework.data.domain.Page;
import java.util.List;

public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        long total
) {
    public static <T> PagedResponse<T> from(Page<T> pageData) {
        return new PagedResponse<>(
                pageData.getContent(),
                pageData.getNumber(),
                pageData.getSize(),
                pageData.getTotalElements()
        );
    }
}
