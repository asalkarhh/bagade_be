package com.bagade.purchasing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CancelPurchaseOrderRemainderRequest(
        @NotNull(message = "Expected version is required")
        @Min(value = 0, message = "Expected version must be 0 or greater")
        Long expectedVersion,

        @NotBlank(message = "Cancellation reason is required")
        @Size(max = 500, message = "Cancellation reason must not exceed 500 characters")
        String reason,

        List<UUID> poLineIds
) {}
