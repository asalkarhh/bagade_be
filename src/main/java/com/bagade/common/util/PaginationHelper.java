package com.bagade.common.util;

import com.bagade.common.error.FieldErrorItem;
import com.bagade.common.error.RequestValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;

public final class PaginationHelper {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;

    private PaginationHelper() {
    }

    public static Pageable createPageable(int page, int size, Sort sort) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (page < 0) {
            errors.add(new FieldErrorItem("page", "Page index must be 0 or greater"));
        }
        if (size < 1 || size > MAX_SIZE) {
            errors.add(new FieldErrorItem("size", "Page size must be between 1 and " + MAX_SIZE));
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException("Validation failed", errors);
        }
        return PageRequest.of(page, size, sort);
    }
}
