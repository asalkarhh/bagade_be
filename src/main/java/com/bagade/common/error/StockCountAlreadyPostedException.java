package com.bagade.common.error;

public class StockCountAlreadyPostedException extends RuntimeException {
    public StockCountAlreadyPostedException(String message) {
        super(message);
    }
}
