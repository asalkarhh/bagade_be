package com.bagade.billing.dto;

import com.bagade.billing.entity.PaymentDirection;
import com.bagade.billing.entity.PaymentMode;

import java.time.Instant;
import java.util.UUID;

public record PaymentEntryResponse(
        UUID paymentId,
        PaymentDirection direction,
        PaymentMode mode,
        String amount,
        String reference,
        Instant postedAt,
        UUID postedBy
) {}
