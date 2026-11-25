package com.bagade.billing.dto;

import com.bagade.billing.entity.BillingEventType;

import java.time.Instant;
import java.util.UUID;

public record SalesAdjustmentResponse(
        UUID adjustmentId,
        BillingEventType adjustmentType,
        String subtotalDelta,
        String discountDelta,
        String amountDelta,
        Instant occurredAt,
        UUID postedBy
) {}
