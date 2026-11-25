package com.bagade.purchasing.repository;

import com.bagade.purchasing.entity.PurchaseOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID>, PurchaseOrderSearchRepository {

    @Query(value = "SELECT nextval('purchase_order_number_seq')", nativeQuery = true)
    long nextPoNumberSequence();

    @Query("""
            SELECT DISTINCT po FROM PurchaseOrder po
            JOIN FETCH po.vendor
            LEFT JOIN FETCH po.lines l
            LEFT JOIN FETCH l.sku s
            LEFT JOIN FETCH s.product
            WHERE po.id = :id
            """)
    Optional<PurchaseOrder> findByIdWithDetails(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT po FROM PurchaseOrder po WHERE po.id = :id")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") UUID id);
}
