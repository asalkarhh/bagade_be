package com.bagade.inventory.dto;

import java.time.LocalDate;
import java.util.UUID;

public record EligibleBatchResponse(
        UUID batchId,
        LocalDate receivedDate,
        String printedMrp,
        long sellableQuantity,
        String internalBarcode
) {}
