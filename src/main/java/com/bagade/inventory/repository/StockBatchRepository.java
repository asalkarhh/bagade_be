package com.bagade.inventory.repository;

import com.bagade.inventory.entity.StockBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockBatchRepository extends JpaRepository<StockBatch, UUID> {

    List<StockBatch> findByStockReceiptLineIdIn(Collection<UUID> stockReceiptLineIds);

    @Query("SELECT b FROM StockBatch b JOIN FETCH b.sku s JOIN FETCH s.product WHERE b.id = :id")
    Optional<StockBatch> findByIdWithSkuAndProduct(@Param("id") UUID id);
}
