package com.bagade.inventory.dto;

import java.util.List;
import java.util.UUID;

public record StockCountAdjustmentSummaryResponse(
        UUID skuId,
        String skuCode,
        String productName,
        String size,
        int expectedQuantity,
        int countedQuantity,
        int quantityDelta,
        String reason,
        String allocationBasis,
        List<UUID> batchIds
) {}
