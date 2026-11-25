package com.bagade.purchasing.repository;

import com.bagade.purchasing.entity.PurchaseTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PurchaseTargetRepository extends JpaRepository<PurchaseTarget, UUID> {

    List<PurchaseTarget> findBySkuIdIn(Collection<UUID> skuIds);

    @Modifying
    @Query(value = """
            INSERT INTO purchase_target (id, sku_id, planning_period, target_quantity, created_at, updated_at, updated_by)
            VALUES (gen_random_uuid(), :skuId, NULL, :targetQuantity, :now, :now, :actorUserId)
            ON CONFLICT (sku_id) WHERE planning_period IS NULL
            DO UPDATE SET
                target_quantity = EXCLUDED.target_quantity,
                updated_at = EXCLUDED.updated_at,
                updated_by = EXCLUDED.updated_by
            """, nativeQuery = true)
    void upsertDefaultTarget(
            @Param("skuId") UUID skuId,
            @Param("targetQuantity") int targetQuantity,
            @Param("now") Instant now,
            @Param("actorUserId") UUID actorUserId
    );

    @Modifying
    @Query(value = """
            INSERT INTO purchase_target (id, sku_id, planning_period, target_quantity, created_at, updated_at, updated_by)
            VALUES (gen_random_uuid(), :skuId, :planningPeriod, :targetQuantity, :now, :now, :actorUserId)
            ON CONFLICT (sku_id, LOWER(planning_period)) WHERE planning_period IS NOT NULL
            DO UPDATE SET
                planning_period = EXCLUDED.planning_period,
                target_quantity = EXCLUDED.target_quantity,
                updated_at = EXCLUDED.updated_at,
                updated_by = EXCLUDED.updated_by
            """, nativeQuery = true)
    void upsertPeriodTarget(
            @Param("skuId") UUID skuId,
            @Param("planningPeriod") String planningPeriod,
            @Param("targetQuantity") int targetQuantity,
            @Param("now") Instant now,
            @Param("actorUserId") UUID actorUserId
    );
}
