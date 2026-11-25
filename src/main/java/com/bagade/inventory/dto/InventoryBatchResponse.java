package com.bagade.inventory.dto;

import java.time.LocalDate;
import java.util.UUID;

public record InventoryBatchResponse(
        UUID batchId,
        LocalDate receivedDate,
        String unitCost,
        String printedMrp,
        int sellableQuantity,
        int nonSellableQuantity,
        String internalBarcode
) {}
