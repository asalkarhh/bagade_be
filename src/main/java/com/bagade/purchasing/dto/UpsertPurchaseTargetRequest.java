package com.bagade.purchasing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpsertPurchaseTargetRequest(
        @NotNull(message = "Target quantity is required")
        @Min(value = 0, message = "Target quantity must be 0 or greater")
        Integer targetQuantity,

        @Size(max = 50, message = "Planning period must not exceed 50 characters")
        String planningPeriod
) {}
