package com.bagade.inventory.repository;

import com.bagade.inventory.entity.StockCountLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockCountLineRepository extends JpaRepository<StockCountLine, UUID> {

    @Query("""
            SELECT l FROM StockCountLine l
            JOIN FETCH l.sku s
            JOIN FETCH s.product
            WHERE l.stockCount.id = :countId
            ORDER BY l.lineNumber ASC, l.id ASC
            """)
    List<StockCountLine> findByStockCountIdWithSkuOrderByLineNumberAsc(@Param("countId") UUID countId);

    @Query("""
            SELECT l FROM StockCountLine l
            JOIN FETCH l.sku s
            JOIN FETCH s.product
            WHERE l.stockCount.id = :countId AND l.sku.id = :skuId
            """)
    Optional<StockCountLine> findByStockCountIdAndSkuId(@Param("countId") UUID countId, @Param("skuId") UUID skuId);
}
