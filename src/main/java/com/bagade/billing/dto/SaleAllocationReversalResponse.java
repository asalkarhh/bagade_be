package com.bagade.billing.dto;

import com.bagade.inventory.entity.StockCondition;

import java.time.Instant;
import java.util.UUID;

public record SaleAllocationReversalResponse(
        UUID reversalId,
        UUID saleAllocationId,
        UUID billingEventId,
        UUID skuId,
        UUID batchId,
        int quantity,
        String capturedUnitCost,
        StockCondition disposition,
        Instant reversedAt
) {}
