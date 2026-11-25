package com.bagade.billing.dto;

import com.bagade.billing.entity.PaymentDirection;
import com.bagade.billing.entity.PaymentMode;

import java.time.Instant;

public record BillPrintPaymentResponse(
        PaymentDirection direction,
        PaymentMode mode,
        String amount,
        String reference,
        Instant postedAt
) {}
