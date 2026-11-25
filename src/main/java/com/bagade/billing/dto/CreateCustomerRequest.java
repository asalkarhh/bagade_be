package com.bagade.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequest(
        @NotBlank(message = "Customer name is required")
        @Size(max = 200, message = "Customer name must not exceed 200 characters")
        String name,

        @Size(max = 200, message = "Contact must not exceed 200 characters")
        String contact
) {}
