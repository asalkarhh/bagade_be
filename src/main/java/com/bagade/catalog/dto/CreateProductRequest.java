package com.bagade.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateProductRequest(
        @NotNull(message = "Category ID is required")
        UUID categoryId,

        @NotBlank(message = "Product name is required")
        @Size(max = 200, message = "Product name must not exceed 200 characters")
        String name,

        @Size(max = 150, message = "Brand must not exceed 150 characters")
        String brand,

        @Size(max = 150, message = "Design must not exceed 150 characters")
        String design
) {}
