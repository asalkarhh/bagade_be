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
}
