package com.bagade.billing.repository;

import com.bagade.billing.entity.BillRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillRevisionRepository extends JpaRepository<BillRevision, UUID> {

    List<BillRevision> findByBillIdOrderByRevisionNumberAsc(UUID billId);

    Optional<BillRevision> findByBillIdAndRevisionNumber(UUID billId, int revisionNumber);

    @Query("""
            SELECT DISTINCT br FROM BillRevision br
            LEFT JOIN FETCH br.lines l
            LEFT JOIN FETCH l.sku
            WHERE br.bill.id = :billId AND br.revisionNumber = :revisionNumber
            """)
    Optional<BillRevision> findByBillIdAndRevisionNumberWithLines(
            @Param("billId") UUID billId,
            @Param("revisionNumber") int revisionNumber
    );
}
