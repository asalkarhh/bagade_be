package com.bagade.purchasing.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PurchasePlanningItemResponse(
        UUID skuId,
        String skuCode,
        String productName,
        String size,
        String currentSellingPrice,
        long sellableQuantity,
        long openPoQuantity,
        long recentSales,
        LocalDate lastVerifiedCountDate,
        List<PastVendorRefResponse> pastVendors,
        Integer targetQuantity,
        String resolvedPlanningPeriod,
        Long suggestedQuantity
) {}
