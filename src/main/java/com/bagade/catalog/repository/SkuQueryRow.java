package com.bagade.catalog.repository;

import java.math.BigDecimal;
import java.util.UUID;

public record SkuQueryRow(
        UUID skuId,
        String productName,
        String size,
        String skuCode,
        BigDecimal defaultSellingPrice,
        boolean active,
        long version
) {}
