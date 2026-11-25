package com.bagade.catalog.dto;

import com.bagade.catalog.validation.ValidInrPrice;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSkuRequest(
        @NotBlank(message = "SKU code is required")
        @Size(max = 100, message = "SKU code must not exceed 100 characters")
        String skuCode,

        @Size(max = 50, message = "Size must not exceed 50 characters")
        String size,

        @ValidInrPrice(allowNull = false)
        String defaultSellingPrice
) {}
