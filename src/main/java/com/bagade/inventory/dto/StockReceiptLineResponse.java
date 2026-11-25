package com.bagade.inventory.dto;

import java.util.UUID;

public record StockReceiptLineResponse(
        UUID receiptLineId,
        UUID skuId,
        String skuCode,
        String productName,
        String size,
        UUID poLineId,
        Integer poLineOpenQuantity,
        UUID batchId,
        int quantity,
        String unitCost,
        String printedMrp,
        String internalBarcode
) {}
