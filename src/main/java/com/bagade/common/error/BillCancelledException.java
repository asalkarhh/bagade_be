package com.bagade.common.error;

public class BillCancelledException extends RuntimeException {
    public BillCancelledException(String message) {
        super(message);
    }
}
