package com.bagade.catalog.dto;

import java.util.UUID;

public record CreateSkuResponse(
        UUID skuId,
        String skuCode,
        String size,
        String defaultSellingPrice,
        long version
) {}
