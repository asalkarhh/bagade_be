package com.bagade.inventory.dto;

import com.bagade.purchasing.entity.PurchaseOrderStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StockReceiptResponse(
        UUID receiptId,
        String receiptNumber,
        UUID vendorId,
        String vendorName,
        UUID poId,
        String poNumber,
        PurchaseOrderStatus poStatus,
        Long poTotalOpenQuantity,
        Long poVersion,
        String vendorInvoiceRef,
        LocalDate receivedDate,
        Instant postedAt,
        List<StockReceiptLineResponse> lines
) {}
