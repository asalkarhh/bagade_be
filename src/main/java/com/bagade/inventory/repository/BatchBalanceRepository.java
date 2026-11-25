package com.bagade.inventory.repository;

import com.bagade.inventory.entity.BatchBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface BatchBalanceRepository extends JpaRepository<BatchBalance, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT bb FROM BatchBalance bb
            JOIN FETCH bb.batch b
            WHERE b.sku.id IN :skuIds
              AND bb.stockCondition = com.bagade.inventory.entity.StockCondition.SELLABLE
            ORDER BY b.receivedDate ASC, b.createdAt ASC, b.id ASC
            """)
    List<BatchBalance> findSellableBalancesForSkusForUpdate(@Param("skuIds") Collection<UUID> skuIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT bb FROM BatchBalance bb
            JOIN FETCH bb.batch b
            WHERE b.sku.id IN :skuIds
            ORDER BY b.receivedDate ASC, b.createdAt ASC, b.id ASC, bb.stockCondition ASC
            """)
    List<BatchBalance> findAllBalancesForSkusForUpdate(@Param("skuIds") Collection<UUID> skuIds);

    @Query("""
            SELECT bb FROM BatchBalance bb
            JOIN FETCH bb.batch b
            WHERE b.sku.id IN :skuIds
              AND bb.stockCondition = com.bagade.inventory.entity.StockCondition.SELLABLE
              AND bb.quantity > 0
            ORDER BY b.receivedDate ASC, b.createdAt ASC, b.id ASC
            """)
    List<BatchBalance> findSellablePositiveBalancesForSkus(@Param("skuIds") Collection<UUID> skuIds);

    @Query("""
            SELECT b.sku.id, COALESCE(SUM(bb.quantity), 0L)
            FROM BatchBalance bb
            JOIN bb.batch b
            WHERE b.sku.id IN :skuIds AND bb.stockCondition = com.bagade.inventory.entity.StockCondition.SELLABLE
            GROUP BY b.sku.id
            """)
    List<Object[]> sumSellableBySkuIds(@Param("skuIds") Collection<UUID> skuIds);

    @Query("""
            SELECT COALESCE(SUM(bb.quantity), 0L)
            FROM BatchBalance bb
            WHERE bb.batch.id = :batchId AND bb.stockCondition = com.bagade.inventory.entity.StockCondition.SELLABLE
            """)
    long sumSellableByBatchId(@Param("batchId") UUID batchId);

    @Query("""
            SELECT new com.bagade.inventory.repository.BatchBalanceRow(
                b.sku.id,
                b.id,
                b.receivedDate,
                b.unitCost,
                b.printedMrp,
                bb.stockCondition,
                bb.quantity,
                bm.code
            )
            FROM BatchBalance bb
            JOIN bb.batch b
            LEFT JOIN BarcodeMapping bm ON bm.batch.id = b.id AND bm.sourceType = com.bagade.inventory.entity.BarcodeSourceType.INTERNAL
            WHERE b.sku.id IN :skuIds
            ORDER BY b.receivedDate ASC, b.createdAt ASC, b.id ASC
            """)
    List<BatchBalanceRow> findBatchBalancesForSkuIds(@Param("skuIds") Collection<UUID> skuIds);
}
