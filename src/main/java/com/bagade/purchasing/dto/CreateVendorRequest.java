package com.bagade.purchasing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateVendorRequest(
        @NotBlank(message = "Vendor name is required")
        @Size(max = 200, message = "Vendor name must not exceed 200 characters")
        String name,

        @Size(max = 200, message = "Contact must not exceed 200 characters")
        String contact
) {}
