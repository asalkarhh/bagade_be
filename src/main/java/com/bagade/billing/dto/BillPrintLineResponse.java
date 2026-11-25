package com.bagade.billing.dto;

public record BillPrintLineResponse(
        String skuCode,
        String productName,
        String size,
        int quantity,
        int returnedQuantity,
        String unitSellingPrice,
        String discountAmount,
        String lineTotal
) {}
