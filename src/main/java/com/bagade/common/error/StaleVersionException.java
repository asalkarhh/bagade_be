package com.bagade.common.error;

public class StaleVersionException extends RuntimeException {

    public StaleVersionException(String message) {
        super(message);
    }
}
