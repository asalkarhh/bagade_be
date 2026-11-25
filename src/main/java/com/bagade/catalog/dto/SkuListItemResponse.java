package com.bagade.catalog.dto;

import java.util.UUID;

public record SkuListItemResponse(
        UUID skuId,
        String productName,
        String size,
        String skuCode,
        String defaultSellingPrice,
        int sellableQuantity,
        boolean active,
        long version
) {}
