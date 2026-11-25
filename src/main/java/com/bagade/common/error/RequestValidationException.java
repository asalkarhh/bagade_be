package com.bagade.common.error;

import java.util.List;

public class RequestValidationException extends RuntimeException {

    private final List<FieldErrorItem> fieldErrors;

    public RequestValidationException(String message, List<FieldErrorItem> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }

    public RequestValidationException(String field, String message) {
        super("Validation failed");
        this.fieldErrors = List.of(new FieldErrorItem(field, message));
    }

    public List<FieldErrorItem> getFieldErrors() {
        return fieldErrors;
    }
}
