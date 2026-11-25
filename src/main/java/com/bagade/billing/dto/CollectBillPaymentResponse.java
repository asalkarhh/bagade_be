package com.bagade.billing.dto;

import java.util.UUID;

public record CollectBillPaymentResponse(
        UUID paymentId,
        UUID billId,
        long version,
        String balanceDue
) {}
