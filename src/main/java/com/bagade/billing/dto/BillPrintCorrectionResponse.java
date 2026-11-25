package com.bagade.billing.dto;

import com.bagade.billing.entity.BillingEventType;

import java.time.Instant;

public record BillPrintCorrectionResponse(
        BillingEventType eventType,
        Instant occurredAt,
        String reason,
        String amountDelta
) {}
