package com.bagade.purchasing.repository;

import com.bagade.purchasing.entity.PurchaseOrderStatus;

import java.time.LocalDate;
import java.util.UUID;

public record PurchaseOrderListRow(
        UUID poId,
        String poNumber,
        UUID vendorId,
        LocalDate orderDate,
        PurchaseOrderStatus status,
        Long totalOpenQuantity,
        Long version
) {}
