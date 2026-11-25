package com.bagade.common.error;

import java.time.LocalDate;
import java.util.UUID;

public record SelectableBatchDetail(
        UUID skuId,
        UUID batchId,
        LocalDate receivedDate,
        String printedMrp,
        long sellableQuantity,
        String internalBarcode
) {}
