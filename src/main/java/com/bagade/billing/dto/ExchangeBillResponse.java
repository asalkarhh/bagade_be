package com.bagade.billing.dto;

import java.util.List;
import java.util.UUID;

public record ExchangeBillResponse(
        UUID exchangeId,
        UUID billId,
        long version,
        String priceDifference,
        String balanceDue,
        List<BillStockEffectResponse> stockEffects
) {}
