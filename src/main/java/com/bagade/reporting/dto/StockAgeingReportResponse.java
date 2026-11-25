package com.bagade.reporting.dto;

import java.time.LocalDate;
import java.util.List;

public record StockAgeingReportResponse(
        LocalDate asOfDate,
        long totalSellableUnits,
        String totalStockValue,
        boolean costComplete,
        long unknownReceiptDateUnits,
        long unknownCostUnits,
        List<StockAgeingRowResponse> items,
        int page,
        int size,
        long total
) {}
