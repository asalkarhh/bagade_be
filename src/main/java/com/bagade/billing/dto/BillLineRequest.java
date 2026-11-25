package com.bagade.billing.dto;

import com.bagade.catalog.validation.ValidInrPrice;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record BillLineRequest(
        @NotNull(message = "SKU ID is required")
        UUID skuId,

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be greater than 0")
        Integer quantity,

        UUID batchId,

        @ValidInrPrice(allowNull = true)
        String discountAmount,

        @ValidInrPrice(allowNull = true)
        String unitPriceOverride
) {}
