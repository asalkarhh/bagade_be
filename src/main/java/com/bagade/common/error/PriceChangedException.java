package com.bagade.common.error;

public class PriceChangedException extends RuntimeException {

    private final BillTotalsSnapshot currentTotals;

    public PriceChangedException(String message, BillTotalsSnapshot currentTotals) {
        super(message);
        this.currentTotals = currentTotals;
    }

    public BillTotalsSnapshot getCurrentTotals() {
        return currentTotals;
    }
}
