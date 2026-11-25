package com.bagade.reporting.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SalesReportResponse(
        LocalDate from,
        LocalDate to,
        UUID categoryId,
        UUID skuId,
        String minPrice,
        String maxPrice,
        long netUnitsSold,
        String netSales,
        String cogs,
        String grossProfit,
        boolean costComplete,
        long nonSellableReturnUnits,
        String nonSellableReturnCost,
        String nonSellableReturnSalesValue,
        String operatingExpenses,
        String estimatedProfit,
        List<SalesReportItemResponse> items,
        int page,
        int size,
        long total
) {}
