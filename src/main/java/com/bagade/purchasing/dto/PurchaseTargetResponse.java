package com.bagade.purchasing.dto;

import java.util.UUID;

public record PurchaseTargetResponse(
        UUID skuId,
        int targetQuantity,
        String planningPeriod
) {}
