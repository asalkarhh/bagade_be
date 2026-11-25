package com.bagade.billing.dto;

import com.bagade.billing.entity.PaymentMode;
import com.bagade.catalog.validation.ValidInrPrice;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BillPaymentRequest(
        @NotNull(message = "Payment mode is required")
        PaymentMode mode,

        @ValidInrPrice(allowNull = false)
        String amount,

        @Size(max = 150, message = "Payment reference must not exceed 150 characters")
        String reference
) {}
