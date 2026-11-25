package com.bagade.billing.dto;

import com.bagade.billing.entity.BillStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BillDetailResponse(
        UUID billId,
        String billNumber,
        LocalDate issueDate,
        Instant issuedAt,
        BillStatus status,
        int currentRevisionNumber,
        long version,
        UUID customerId,
        String customerName,
        String customerContact,
        String subtotal,
        String discountTotal,
        String total,
        String paid,
        String balanceDue,
        List<BillLineDetailResponse> lines,
        List<PaymentEntryResponse> payments,
        List<BillRevisionRefResponse> revisions,
        List<BillingEventRefResponse> events,
        List<SalesAdjustmentResponse> salesAdjustments,
        List<SaleAllocationReversalResponse> allocationReversals
) {}
