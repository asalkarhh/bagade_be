package com.bagade.reporting.dto;

import java.util.UUID;

public record SalesReportItemResponse(
        UUID skuId,
        String skuCode,
        String productName,
        String size,
        long netUnitsSold,
        String netSales,
        String cogs,
        String grossProfit,
        boolean costComplete,
        long nonSellableReturnUnits,
        String nonSellableReturnCost,
        String nonSellableReturnSalesValue
) {}
