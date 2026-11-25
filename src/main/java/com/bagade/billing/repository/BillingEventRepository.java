package com.bagade.billing.repository;

import com.bagade.billing.entity.BillingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BillingEventRepository extends JpaRepository<BillingEvent, UUID> {

    @Query("SELECT e FROM BillingEvent e JOIN FETCH e.billRevision WHERE e.bill.id = :billId ORDER BY e.occurredAt ASC, e.id ASC")
    List<BillingEvent> findByBillIdWithRevisionOrderByOccurredAtAsc(@Param("billId") UUID billId);
}
