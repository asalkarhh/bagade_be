package com.bagade.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateStockCountLineRequest(
        @NotNull(message = "Expected version is required")
        @Min(value = 0, message = "Expected version must be 0 or greater")
        Long expectedVersion,

        @Min(value = 0, message = "Counted quantity must be 0 or greater")
        Integer countedQuantity,

        @NotNull(message = "Observed stock version is required")
        @Min(value = 0, message = "Observed stock version must be 0 or greater")
        Long observedStockVersion,

        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason
) {}
