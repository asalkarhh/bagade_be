package com.bagade.billing.repository;

import com.bagade.billing.entity.SalesAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SalesAdjustmentRepository extends JpaRepository<SalesAdjustment, UUID> {

    List<SalesAdjustment> findByBillIdOrderByOccurredAtAscIdAsc(UUID billId);
}
