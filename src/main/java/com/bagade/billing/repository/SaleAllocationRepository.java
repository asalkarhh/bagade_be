package com.bagade.billing.repository;

import com.bagade.billing.entity.SaleAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface SaleAllocationRepository extends JpaRepository<SaleAllocation, UUID> {

    List<SaleAllocation> findByBillLineIdInOrderByIdAsc(Collection<UUID> billLineIds);

    @Query("""
            SELECT sa FROM SaleAllocation sa
            JOIN FETCH sa.batch b
            JOIN FETCH sa.sku s
            JOIN sa.billLine bl
            JOIN bl.billRevision br
            WHERE sa.billId = :billId
            ORDER BY br.revisionNumber ASC, b.receivedDate ASC, b.createdAt ASC, sa.id ASC
            """)
    List<SaleAllocation> findByBillIdOrdered(@Param("billId") UUID billId);

    @Query("""
            SELECT sa FROM SaleAllocation sa
            JOIN FETCH sa.batch b
            JOIN FETCH sa.sku s
            JOIN sa.billLine bl
            JOIN bl.billRevision br
            WHERE sa.billId = :billId
              AND sa.logicalLineId IN :logicalLineIds
            ORDER BY br.revisionNumber ASC, b.receivedDate ASC, b.createdAt ASC, sa.id ASC
            """)
    List<SaleAllocation> findByBillIdAndLogicalLineIdInOrdered(
            @Param("billId") UUID billId,
            @Param("logicalLineIds") Collection<UUID> logicalLineIds
    );
}
