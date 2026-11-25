package com.bagade.billing.dto;

import com.bagade.inventory.entity.MovementType;
import com.bagade.inventory.entity.StockCondition;

import java.util.UUID;

public record BillStockEffectResponse(
        UUID skuId,
        String skuCode,
        UUID batchId,
        StockCondition stockCondition,
        MovementType movementType,
        int quantityDelta,
        int resultingBatchQuantity
) {}
