package com.bagade.reporting.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CollectionsReportResponse(
        LocalDate from,
        LocalDate to,
        UUID customerId,
        String cashCollected,
        String upiCollected,
        String totalCollections,
        String cashRefunded,
        String upiRefunded,
        String totalRefunds,
        String netCollections,
        String creditSales,
        String outstandingBalanceAsOfEnd,
        List<CustomerCollectionRowResponse> items,
        int page,
        int size,
        long total
) {}
