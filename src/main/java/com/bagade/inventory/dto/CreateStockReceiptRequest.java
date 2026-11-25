package com.bagade.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateStockReceiptRequest(
        @NotNull(message = "Vendor ID is required")
        UUID vendorId,

        @NotNull(message = "Received date is required")
        LocalDate receivedDate,

        UUID poId,

        @Size(max = 150, message = "Vendor invoice reference must not exceed 150 characters")
        String vendorInvoiceRef,

        @NotEmpty(message = "At least one receipt line is required")
        @Valid
        List<CreateStockReceiptLineRequest> lines
) {}
