package com.bagade.purchasing.repository;

import com.bagade.purchasing.entity.PurchaseOrderLine;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT l FROM PurchaseOrderLine l
            WHERE l.purchaseOrder.id = :poId
            ORDER BY l.lineNumber ASC, l.id ASC
            """)
    List<PurchaseOrderLine> findByPurchaseOrderIdForUpdate(@Param("poId") UUID poId);
}
