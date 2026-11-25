package com.bagade.inventory.repository;

import com.bagade.inventory.entity.StockCount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockCountRepository extends JpaRepository<StockCount, UUID> {

    @Query("""
            SELECT DISTINCT sc FROM StockCount sc
            LEFT JOIN FETCH sc.category
            LEFT JOIN FETCH sc.lines l
            LEFT JOIN FETCH l.sku s
            LEFT JOIN FETCH s.product
            WHERE sc.id = :id
            """)
    Optional<StockCount> findByIdWithDetails(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sc FROM StockCount sc WHERE sc.id = :id")
    Optional<StockCount> findByIdForUpdate(@Param("id") UUID id);
}
