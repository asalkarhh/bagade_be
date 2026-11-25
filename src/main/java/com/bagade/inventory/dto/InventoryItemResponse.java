package com.bagade.inventory.dto;

import java.util.List;
import java.util.UUID;

public record InventoryItemResponse(
        UUID skuId,
        String skuCode,
        String productName,
        String size,
        String defaultSellingPrice,
        boolean active,
        long sellableQuantity,
        long nonSellableQuantity,
        long stockVersion,
        List<InventoryBatchResponse> batches
) {}
