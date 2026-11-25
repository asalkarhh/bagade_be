package com.bagade.purchasing.dto;

import java.util.UUID;

public record PurchaseOrderLineResponse(
        UUID poLineId,
        UUID skuId,
        String skuCode,
        String productName,
        String size,
        int orderedQuantity,
        int receivedQuantity,
        int cancelledQuantity,
        int openQuantity,
        String agreedUnitCost
) {}
