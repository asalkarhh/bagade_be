package com.bagade.purchasing.dto;

import com.bagade.catalog.validation.ValidInrPrice;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreatePurchaseOrderLineRequest(
        @NotNull(message = "SKU ID is required")
        UUID skuId,

        @NotNull(message = "Ordered quantity is required")
        @Min(value = 1, message = "Ordered quantity must be greater than 0")
        Integer orderedQuantity,

        @ValidInrPrice(allowNull = true)
        String agreedUnitCost
) {}
