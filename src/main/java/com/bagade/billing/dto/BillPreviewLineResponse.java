package com.bagade.billing.dto;

import com.bagade.inventory.dto.EligibleBatchResponse;

import java.util.List;
import java.util.UUID;

public record BillPreviewLineResponse(
        UUID skuId,
        String skuCode,
        String productName,
        String size,
        UUID batchId,
        int quantity,
        String unitSellingPrice,
        String discountAmount,
        String lineSubtotal,
        String lineTotal,
        long availableQuantity,
        boolean sufficientStock,
        boolean requiresBatchSelection,
        List<EligibleBatchResponse> selectableBatches,
        List<String> issues
) {}
