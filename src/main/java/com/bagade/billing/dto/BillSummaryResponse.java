package com.bagade.billing.dto;

import com.bagade.billing.entity.BillStatus;

import java.time.LocalDate;
import java.util.UUID;

public record BillSummaryResponse(
        UUID billId,
        String billNumber,
        LocalDate issueDate,
        BillStatus status,
        String total,
        String paid,
        String balanceDue
) {}
