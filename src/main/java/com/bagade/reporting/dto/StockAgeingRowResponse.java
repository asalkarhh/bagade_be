package com.bagade.reporting.dto;

import java.time.LocalDate;
import java.util.UUID;

public record StockAgeingRowResponse(
        UUID batchId,
        UUID skuId,
        String skuCode,
        String productName,
        String size,
        String batchSource,
        UUID vendorId,
        String vendorName,
        LocalDate receiptDate,
        boolean receiptDateKnown,
        Long daysOnHand,
        long sellableQuantity,
        String unitCost,
        boolean costKnown,
        String stockValue
) {}
