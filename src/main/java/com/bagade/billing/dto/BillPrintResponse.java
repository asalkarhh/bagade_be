package com.bagade.billing.dto;

import com.bagade.billing.entity.BillStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BillPrintResponse(
        String shopName,
        String receiptProfile,
        UUID billId,
        String billNumber,
        BillStatus status,
        int revisionNumber,
        LocalDate issueDate,
        Instant issuedAt,
        String customerName,
        String customerContact,
        List<BillPrintLineResponse> lines,
        String subtotal,
        String discountTotal,
        String total,
        String paid,
        String balanceDue,
        List<BillPrintPaymentResponse> payments,
        List<BillPrintCorrectionResponse> corrections
) {}
