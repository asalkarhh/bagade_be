package com.bagade.purchasing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreatePurchaseOrderRequest(
        @NotNull(message = "Vendor ID is required")
        UUID vendorId,

        @NotNull(message = "Order date is required")
        LocalDate orderDate,

        @NotEmpty(message = "At least one purchase order line is required")
        @Valid
        List<CreatePurchaseOrderLineRequest> lines
) {}
