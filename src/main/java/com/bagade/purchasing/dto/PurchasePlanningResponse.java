package com.bagade.purchasing.dto;

import java.time.LocalDate;
import java.util.List;

public record PurchasePlanningResponse(
        LocalDate recentSalesFrom,
        LocalDate recentSalesTo,
        String planningPeriod,
        List<PurchasePlanningItemResponse> items,
        int page,
        int size,
        long total
) {}
