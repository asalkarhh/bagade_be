package com.bagade.common.error;

public class LabelNotFoundException extends RuntimeException {

    public LabelNotFoundException(String message) {
        super(message);
    }
}
