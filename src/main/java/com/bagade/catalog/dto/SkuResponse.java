package com.bagade.catalog.dto;

import java.util.UUID;

public record SkuResponse(
        UUID skuId,
        UUID productId,
        String productName,
        String skuCode,
        String size,
        String defaultSellingPrice,
        int sellableQuantity,
        boolean active,
        long version
) {}
