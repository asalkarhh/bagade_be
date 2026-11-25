package com.bagade.purchasing.repository;

import com.bagade.purchasing.entity.PurchaseOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.UUID;

public interface PurchaseOrderSearchRepository {

    Page<PurchaseOrderListRow> searchPurchaseOrders(
            UUID vendorId,
            PurchaseOrderStatus status,
            LocalDate from,
            LocalDate to,
            Pageable pageable
    );
}
