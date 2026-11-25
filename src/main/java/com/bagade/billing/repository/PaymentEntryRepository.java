package com.bagade.billing.repository;

import com.bagade.billing.entity.PaymentEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentEntryRepository extends JpaRepository<PaymentEntry, UUID> {

    List<PaymentEntry> findByBillIdOrderByPostedAtAscIdAsc(UUID billId);

    @Query("""
            SELECT p FROM PaymentEntry p
            JOIN p.billRevision br
            WHERE p.bill.id = :billId
              AND br.revisionNumber <= :revisionNumber
            ORDER BY p.postedAt ASC, p.id ASC
            """)
    List<PaymentEntry> findByBillIdUpToRevisionOrderByPostedAtAscIdAsc(
            @Param("billId") UUID billId,
            @Param("revisionNumber") int revisionNumber
    );
}
