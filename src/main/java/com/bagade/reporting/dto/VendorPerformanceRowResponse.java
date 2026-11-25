package com.bagade.reporting.dto;

import java.util.UUID;

public record VendorPerformanceRowResponse(
        UUID vendorId,
        String vendorName,
        String batchSource,
        long receivedUnits,
        long directBatchSoldUnits,
        long fifoSoldUnits,
        long netSoldUnits,
        long remainingSellableUnits,
        String sellThroughPercentage,
        String grossMargin,
        boolean costComplete,
        String weightedDaysFromReceiptToSale
) {}
