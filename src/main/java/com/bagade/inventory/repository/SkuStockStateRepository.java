package com.bagade.inventory.repository;

import com.bagade.inventory.entity.SkuStockState;
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
public interface SkuStockStateRepository extends JpaRepository<SkuStockState, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SkuStockState s WHERE s.skuId IN :skuIds ORDER BY s.skuId ASC")
    List<SkuStockState> findBySkuIdInOrderBySkuIdAscForUpdate(@Param("skuIds") Collection<UUID> skuIds);
}
