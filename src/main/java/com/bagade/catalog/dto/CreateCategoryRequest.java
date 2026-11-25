package com.bagade.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateCategoryRequest(
        @NotBlank(message = "Category name is required")
        @Size(max = 150, message = "Category name must not exceed 150 characters")
        String name,

        UUID parentId
) {}
