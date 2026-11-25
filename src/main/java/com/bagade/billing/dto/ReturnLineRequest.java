package com.bagade.billing.dto;

import com.bagade.inventory.entity.StockCondition;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReturnLineRequest(
        @NotNull(message = "Line ID is required")
        UUID lineId,

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be greater than 0")
        Integer quantity,

        @NotNull(message = "Disposition is required")
        StockCondition disposition
) {}
