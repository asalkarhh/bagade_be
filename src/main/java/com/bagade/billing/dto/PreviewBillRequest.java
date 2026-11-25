package com.bagade.billing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record PreviewBillRequest(
        @NotEmpty(message = "At least one bill line is required")
        @Valid
        List<BillLineRequest> lines
) {}
