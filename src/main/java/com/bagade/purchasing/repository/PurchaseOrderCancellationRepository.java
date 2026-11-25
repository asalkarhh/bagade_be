package com.bagade.purchasing.repository;

import com.bagade.purchasing.entity.PurchaseOrderCancellation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PurchaseOrderCancellationRepository extends JpaRepository<PurchaseOrderCancellation, UUID> {

    List<PurchaseOrderCancellation> findByPurchaseOrderIdOrderByCancelledAtAsc(UUID purchaseOrderId);
}
