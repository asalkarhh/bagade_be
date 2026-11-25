package com.bagade.inventory.dto;

import com.bagade.inventory.entity.StockCountScope;
import com.bagade.inventory.entity.StockCountStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StockCountDetailResponse(
        UUID countId,
        StockCountScope scope,
        UUID categoryId,
        StockCountStatus status,
        long version,
        Instant createdAt,
        Instant postedAt,
        UUID postedBy,
        List<StockCountLineDetailResponse> lines
) {}
