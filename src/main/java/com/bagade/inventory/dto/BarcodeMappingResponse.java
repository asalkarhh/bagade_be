package com.bagade.inventory.dto;

import com.bagade.inventory.entity.BarcodeSourceType;

import java.util.UUID;

public record BarcodeMappingResponse(
        UUID barcodeMappingId,
        String code,
        BarcodeSourceType sourceType,
        UUID skuId,
        UUID batchId
) {}
