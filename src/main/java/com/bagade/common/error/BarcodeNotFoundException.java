package com.bagade.common.error;

public class BarcodeNotFoundException extends RuntimeException {

    public BarcodeNotFoundException(String message) {
        super(message);
    }
}
