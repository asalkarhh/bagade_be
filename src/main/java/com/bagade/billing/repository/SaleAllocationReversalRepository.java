package com.bagade.billing.repository;

import com.bagade.billing.entity.SaleAllocationReversal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SaleAllocationReversalRepository extends JpaRepository<SaleAllocationReversal, UUID> {

    @Query("""
            SELECT r FROM SaleAllocationReversal r
            JOIN FETCH r.saleAllocation sa
            JOIN FETCH r.billingEvent be
            JOIN FETCH r.batch b
            JOIN FETCH r.sku s
            WHERE be.bill.id = :billId
            ORDER BY r.reversedAt ASC, r.id ASC
            """)
    List<SaleAllocationReversal> findByBillIdOrderByReversedAtAsc(@Param("billId") UUID billId);
}
