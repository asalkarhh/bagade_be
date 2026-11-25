package com.bagade.inventory.dto;

import com.bagade.inventory.entity.StockCountStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PostStockCountResponse(
        UUID countId,
        StockCountStatus status,
        long version,
        Instant postedAt,
        List<StockCountAdjustmentSummaryResponse> adjustments
) {}
