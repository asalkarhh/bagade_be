package com.bagade.billing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReturnBillRequest(
        @NotNull(message = "Expected version is required")
        @Min(value = 0, message = "Expected version must be 0 or greater")
        Long expectedVersion,

        @NotBlank(message = "Return reason is required")
        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason,

        @NotEmpty(message = "At least one returned line is required")
        @Valid
        List<ReturnLineRequest> returnedLines,

        @Valid
        List<SettlementEntryRequest> settlement
) {}
