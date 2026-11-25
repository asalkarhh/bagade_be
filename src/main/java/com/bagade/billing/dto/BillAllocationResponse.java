package com.bagade.billing.dto;

import com.bagade.billing.entity.AllocationAttributionMethod;

import java.util.UUID;

public record BillAllocationResponse(
        UUID skuId,
        UUID batchId,
        int quantity,
        String capturedUnitCost,
        AllocationAttributionMethod attributionMethod
) {}
