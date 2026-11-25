package com.bagade.billing.dto;

import com.bagade.catalog.validation.ValidInrPrice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record CreateBillRequest(
        @NotEmpty(message = "At least one bill line is required")
        @Valid
        List<BillLineRequest> lines,

        @ValidInrPrice(allowNull = false)
        String expectedTotal,

        UUID customerId,

        @Valid
        List<BillPaymentRequest> payments
) {}
