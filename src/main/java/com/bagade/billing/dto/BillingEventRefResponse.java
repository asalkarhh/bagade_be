package com.bagade.billing.dto;

import com.bagade.billing.entity.BillingEventType;

import java.time.Instant;
import java.util.UUID;

public record BillingEventRefResponse(
        UUID eventId,
        BillingEventType eventType,
        int revisionNumber,
        String reason,
        Instant occurredAt,
        UUID actorId
) {}
