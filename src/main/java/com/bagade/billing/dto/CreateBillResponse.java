package com.bagade.billing.dto;

import com.bagade.billing.entity.BillStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateBillResponse(
        UUID billId,
        String billNumber,
        long version,
        LocalDate issueDate,
        Instant issuedAt,
        BillStatus status,
        String subtotal,
        String discountTotal,
        String total,
        String paid,
        String balanceDue,
        List<BillAllocationResponse> allocations
) {}
