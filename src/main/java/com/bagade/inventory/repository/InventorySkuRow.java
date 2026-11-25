package com.bagade.inventory.repository;

import java.math.BigDecimal;
import java.util.UUID;

public record InventorySkuRow(
        UUID skuId,
        String skuCode,
        String productName,
        String size,
        BigDecimal defaultSellingPrice,
        boolean active,
        long stockVersion
) {}
