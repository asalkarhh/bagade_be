package com.bagade.inventory.dto;

import java.util.UUID;

public record BatchLabelResponse(
        UUID batchId,
        String code,
        String shopName,
        String productName,
        String size,
        String displayPrice,
        String barcode,
        String textCode
) {}
