package com.bagade.common.error;

public class BarcodeConflictException extends RuntimeException {

    public BarcodeConflictException(String message) {
        super(message);
    }
}
