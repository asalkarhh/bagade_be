package com.bagade.common.error;

import java.util.UUID;

public record ChangedStockSkuDetail(
        UUID skuId,
        String skuCode,
        long observedStockVersion,
        long currentStockVersion,
        long currentSellableQuantity
) {}
