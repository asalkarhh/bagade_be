package com.bagade.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RegisterBarcodeRequest(
        @NotBlank(message = "Barcode is required")
        @Size(max = 150, message = "Barcode must not exceed 150 characters")
        String code,

        @NotNull(message = "SKU ID is required")
        UUID skuId
) {}
