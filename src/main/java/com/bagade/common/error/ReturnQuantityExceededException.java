package com.bagade.common.error;

public class ReturnQuantityExceededException extends RuntimeException {
    public ReturnQuantityExceededException(String message) {
        super(message);
    }
}
