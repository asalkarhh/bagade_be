package com.bagade.billing.dto;

import java.util.List;
import java.util.UUID;

public record BillLineDetailResponse(
        UUID billLineId,
        UUID logicalLineId,
        UUID skuId,
        String skuCode,
        String productName,
        String size,
        int quantity,
        int returnedQuantity,
        int returnableQuantity,
        String unitSellingPrice,
        String discountAmount,
        String lineTotal,
        List<BillAllocationResponse> allocations
) {}
