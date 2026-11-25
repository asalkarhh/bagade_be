package com.bagade.inventory.repository;

import com.bagade.inventory.entity.BarcodeMapping;
import com.bagade.inventory.entity.BarcodeSourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BarcodeMappingRepository extends JpaRepository<BarcodeMapping, UUID> {

    @Query(value = "SELECT nextval('internal_barcode_seq')", nativeQuery = true)
    long nextInternalBarcodeSequence();

    Optional<BarcodeMapping> findByCode(String code);

    @Query("""
            SELECT bm FROM BarcodeMapping bm
            JOIN FETCH bm.sku s
            JOIN FETCH s.product p
            LEFT JOIN FETCH bm.batch b
            WHERE bm.code = :code
            """)
    Optional<BarcodeMapping> findByCodeWithDetails(@Param("code") String code);

    Optional<BarcodeMapping> findByBatchIdAndSourceType(UUID batchId, BarcodeSourceType sourceType);

    List<BarcodeMapping> findByBatchIdInAndSourceType(Collection<UUID> batchIds, BarcodeSourceType sourceType);
}
