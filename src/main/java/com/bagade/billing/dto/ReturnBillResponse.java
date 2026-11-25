package com.bagade.billing.dto;

import java.util.List;
import java.util.UUID;

public record ReturnBillResponse(
        UUID returnId,
        UUID billId,
        long version,
        String balanceDue,
        List<BillStockEffectResponse> stockEffects
) {}
