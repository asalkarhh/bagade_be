package com.bagade.reporting.dto;

import java.time.LocalDate;
import java.util.List;

public record StockAuditReportResponse(
        LocalDate from,
        LocalDate to,
        long totalPositiveAdjustment,
        long totalNegativeAdjustment,
        long netAdjustment,
        List<StockAuditRowResponse> items,
        int page,
        int size,
        long total
) {}
