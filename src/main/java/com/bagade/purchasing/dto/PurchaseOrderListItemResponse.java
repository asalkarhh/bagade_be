package com.bagade.purchasing.dto;

import com.bagade.purchasing.entity.PurchaseOrderStatus;

import java.time.LocalDate;
import java.util.UUID;

public record PurchaseOrderListItemResponse(
        UUID poId,
        String poNumber,
        UUID vendorId,
        LocalDate orderDate,
        PurchaseOrderStatus status,
        long totalOpenQuantity,
        long version
) {}
