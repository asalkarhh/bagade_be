package com.bagade.inventory.repository;

import com.bagade.inventory.entity.StockReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockReceiptRepository extends JpaRepository<StockReceipt, UUID> {

    @Query(value = "SELECT nextval('stock_receipt_number_seq')", nativeQuery = true)
    long nextReceiptNumberSequence();

    @Query("""
            SELECT DISTINCT r FROM StockReceipt r
            JOIN FETCH r.vendor
            LEFT JOIN FETCH r.purchaseOrder
            LEFT JOIN FETCH r.lines l
            LEFT JOIN FETCH l.sku s
            LEFT JOIN FETCH s.product
            LEFT JOIN FETCH l.purchaseOrderLine
            WHERE r.id = :id
            """)
    Optional<StockReceipt> findByIdWithDetails(@Param("id") UUID id);
}
