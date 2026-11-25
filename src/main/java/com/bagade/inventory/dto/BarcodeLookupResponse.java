package com.bagade.inventory.dto;

import com.bagade.inventory.entity.BarcodeSourceType;

import java.util.List;
import java.util.UUID;

public record BarcodeLookupResponse(
        String code,
        BarcodeSourceType mappingType,
        UUID skuId,
        String name,
        String size,
        String defaultSellingPrice,
        long sellableQuantity,
        UUID batchId,
        String printedMrp,
        Long batchSellableQuantity,
        boolean requiresBatchSelection,
        List<EligibleBatchResponse> eligibleBatches
) {}
