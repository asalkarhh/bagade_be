package com.bagade.common.error;

public class PaymentExceedsBalanceException extends RuntimeException {
    public PaymentExceedsBalanceException(String message) {
        super(message);
    }
}
