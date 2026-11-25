package com.bagade.reporting.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record VendorReportResponse(
        LocalDate from,
        LocalDate to,
        LocalDate snapshotDate,
        UUID vendorId,
        UUID categoryId,
        long totalReceivedUnits,
        long totalDirectBatchSoldUnits,
        long totalFifoSoldUnits,
        long totalNetSoldUnits,
        long totalRemainingSellableUnits,
        String totalSellThroughPercentage,
        String totalGrossMargin,
        boolean costComplete,
        String weightedDaysFromReceiptToSale,
        List<VendorPerformanceRowResponse> items,
        int page,
        int size,
        long total
) {}
