package com.bagade.billing.dto;

import java.time.Instant;
import java.util.UUID;

public record BillRevisionRefResponse(
        UUID revisionId,
        int revisionNumber,
        String subtotal,
        String discountTotal,
        String total,
        Instant createdAt
) {}
