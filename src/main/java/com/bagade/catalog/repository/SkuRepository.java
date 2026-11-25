package com.bagade.catalog.repository;

import com.bagade.catalog.entity.Sku;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SkuRepository extends JpaRepository<Sku, UUID>, SkuSearchRepository {

    @Query("SELECT s FROM Sku s JOIN FETCH s.product WHERE s.id = :id")
    Optional<Sku> findByIdWithProduct(@Param("id") UUID id);

    @Query("SELECT s FROM Sku s JOIN FETCH s.product WHERE s.id IN :ids")
    List<Sku> findAllByIdWithProduct(@Param("ids") Collection<UUID> ids);

    @Query("""
            SELECT s FROM Sku s
            JOIN FETCH s.product p
            WHERE (s.active = true OR EXISTS (
                SELECT bb.id FROM BatchBalance bb
                JOIN bb.batch b
                WHERE b.sku.id = s.id
                  AND bb.stockCondition = com.bagade.inventory.entity.StockCondition.SELLABLE
                  AND bb.quantity > 0
            ))
            ORDER BY p.name ASC, s.skuCode ASC, s.id ASC
            """)
    List<Sku> findCountableSkusForShop();

    @Query("""
            SELECT s FROM Sku s
            JOIN FETCH s.product p
            WHERE p.category.id IN :categoryIds
              AND (s.active = true OR EXISTS (
                SELECT bb.id FROM BatchBalance bb
                JOIN bb.batch b
                WHERE b.sku.id = s.id
                  AND bb.stockCondition = com.bagade.inventory.entity.StockCondition.SELLABLE
                  AND bb.quantity > 0
            ))
            ORDER BY p.name ASC, s.skuCode ASC, s.id ASC
            """)
    List<Sku> findCountableSkusForCategories(@Param("categoryIds") Collection<UUID> categoryIds);
}
