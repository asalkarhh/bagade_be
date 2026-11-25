package com.bagade.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String code,
        String message,
        String requestId,
        List<FieldErrorItem> fieldErrors
) {
    public ApiErrorResponse(String code, String message, String requestId) {
        this(code, message, requestId, null);
    }
}
