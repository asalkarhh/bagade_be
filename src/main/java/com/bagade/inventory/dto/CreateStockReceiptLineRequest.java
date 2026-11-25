package com.bagade.inventory.dto;

import com.bagade.catalog.validation.ValidInrPrice;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateStockReceiptLineRequest(
        @NotNull(message = "SKU ID is required")
        UUID skuId,

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be greater than 0")
        Integer quantity,

        @ValidInrPrice(allowNull = false)
        String unitCost,

        @ValidInrPrice(allowNull = true)
        String printedMrp,

        UUID poLineId,

        Boolean generateInternalTag
) {}
