package com.bagade.inventory.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.UUID;

public interface InventorySearchRepository {

    Page<InventorySkuRow> searchInventorySkus(
            UUID skuId,
            Collection<UUID> categoryIds,
            String searchPattern,
            Pageable pageable
    );
}
