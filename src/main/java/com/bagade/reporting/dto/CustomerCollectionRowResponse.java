package com.bagade.reporting.dto;

import java.util.UUID;

public record CustomerCollectionRowResponse(
        UUID customerId,
        String customerName,
        String customerContact,
        String cashCollected,
        String upiCollected,
        String totalCollections,
        String totalRefunds,
        String netCollections,
        String creditSales,
        String outstandingBalanceAsOfEnd
) {}
