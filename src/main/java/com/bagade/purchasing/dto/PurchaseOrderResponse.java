package com.bagade.purchasing.dto;

import com.bagade.purchasing.entity.PurchaseOrderStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderResponse(
        UUID poId,
        String poNumber,
        UUID vendorId,
        String vendorName,
        LocalDate orderDate,
        PurchaseOrderStatus status,
        long totalOpenQuantity,
        long version,
        List<PurchaseOrderLineResponse> lines
) {}
