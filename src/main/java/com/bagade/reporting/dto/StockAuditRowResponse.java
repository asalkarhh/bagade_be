package com.bagade.reporting.dto;

import com.bagade.inventory.entity.StockCountScope;

import java.time.Instant;
import java.util.UUID;

public record StockAuditRowResponse(
        UUID stockCountId,
        UUID countLineId,
        StockCountScope scope,
        UUID skuId,
        String skuCode,
        String productName,
        String size,
        int expectedQuantity,
        int countedQuantity,
        int adjustment,
        String reason,
        UUID postedByUserId,
        String postedByUsername,
        String postedByDisplayName,
        Instant postedAt
) {}
