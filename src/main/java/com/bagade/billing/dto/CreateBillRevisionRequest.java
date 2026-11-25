package com.bagade.billing.dto;

import com.bagade.catalog.validation.ValidInrPrice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateBillRevisionRequest(
        @NotNull(message = "Expected version is required")
        @Min(value = 0, message = "Expected version must be 0 or greater")
        Long expectedVersion,

        @NotBlank(message = "Correction reason is required")
        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason,

        @NotEmpty(message = "At least one bill line is required")
        @Valid
        List<EditBillLineRequest> lines,

        @ValidInrPrice(allowNull = false)
        String expectedTotal,

        @Valid
        List<SettlementEntryRequest> settlement
) {}
