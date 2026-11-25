package com.bagade.inventory.dto;

import java.util.UUID;

public record StockCountSkuRefResponse(
        UUID skuId,
        String skuCode,
        String productName,
        String size
) {}
