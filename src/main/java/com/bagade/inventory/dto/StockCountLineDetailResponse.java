package com.bagade.inventory.dto;

import java.util.UUID;

public record StockCountLineDetailResponse(
        UUID skuId,
        String skuCode,
        String productName,
        String size,
        Integer countedQuantity,
        Integer expectedQuantity,
        long currentQuantity,
        Long observedStockVersion,
        long currentStockVersion,
        String reason
) {}
