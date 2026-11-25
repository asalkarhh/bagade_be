package com.bagade.inventory.dto;

import com.bagade.inventory.entity.StockCountScope;
import com.bagade.inventory.entity.StockCountStatus;

import java.util.List;
import java.util.UUID;

public record CreateStockCountResponse(
        UUID countId,
        StockCountScope scope,
        UUID categoryId,
        StockCountStatus status,
        long version,
        List<StockCountSkuRefResponse> scopedSkus
) {}
