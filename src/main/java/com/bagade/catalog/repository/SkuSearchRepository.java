package com.bagade.catalog.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.UUID;

public interface SkuSearchRepository {

    Page<SkuQueryRow> searchSkus(
            String searchPattern,
            Collection<UUID> categoryIds,
            boolean active,
            Pageable pageable
    );
}
