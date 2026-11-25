package com.bagade.common.error;

import java.util.List;

public class StockChangedException extends RuntimeException {

    private final List<ChangedStockSkuDetail> changedSkus;

    public StockChangedException(String message, List<ChangedStockSkuDetail> changedSkus) {
        super(message);
        this.changedSkus = List.copyOf(changedSkus);
    }

    public List<ChangedStockSkuDetail> getChangedSkus() {
        return changedSkus;
    }
}
