package com.bagade.catalog.dto;

import com.bagade.catalog.validation.ValidInrPrice;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateSkuRequest(
        @NotNull(message = "Expected version is required")
        @Min(value = 0, message = "Expected version must be 0 or greater")
        Long expectedVersion,

        @ValidInrPrice(allowNull = true)
        String defaultSellingPrice,

        Boolean active
) {}
