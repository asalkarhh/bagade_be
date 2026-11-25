package com.bagade.inventory.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class InventorySearchRepositoryImpl implements InventorySearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<InventorySkuRow> searchInventorySkus(
            UUID skuId,
            Collection<UUID> categoryIds,
            String searchPattern,
            Pageable pageable) {

        if (categoryIds != null && categoryIds.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<String> predicates = new ArrayList<>();
        if (skuId != null) {
            predicates.add("s.id = :skuId");
        }
        if (categoryIds != null) {
            predicates.add("p.category.id IN :categoryIds");
        }
        if (searchPattern != null) {
            predicates.add("(LOWER(p.name) LIKE :searchPattern ESCAPE '!' "
                    + "OR LOWER(s.skuCode) LIKE :searchPattern ESCAPE '!' "
                    + "OR (s.size IS NOT NULL AND LOWER(s.size) LIKE :searchPattern ESCAPE '!'))");
        }

        String whereClause = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);

        String countJpql = "SELECT COUNT(s.id) FROM Sku s JOIN s.product p" + whereClause;
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
        bindParameters(countQuery, skuId, categoryIds, searchPattern);
        long total = countQuery.getSingleResult();

        if (total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        String selectJpql = """
                SELECT new com.bagade.inventory.repository.InventorySkuRow(
                    s.id,
                    s.skuCode,
                    p.name,
                    s.size,
                    s.defaultSellingPrice,
                    s.active,
                    COALESCE(st.stockVersion, 0L)
                )
                FROM Sku s
                JOIN s.product p
                LEFT JOIN SkuStockState st ON st.skuId = s.id
                """ + whereClause + " ORDER BY p.name ASC, s.skuCode ASC, s.id ASC";

        TypedQuery<InventorySkuRow> dataQuery = entityManager.createQuery(selectJpql, InventorySkuRow.class);
        bindParameters(dataQuery, skuId, categoryIds, searchPattern);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        return new PageImpl<>(dataQuery.getResultList(), pageable, total);
    }

    private void bindParameters(
            TypedQuery<?> query,
            UUID skuId,
            Collection<UUID> categoryIds,
            String searchPattern) {
        if (skuId != null) {
            query.setParameter("skuId", skuId);
        }
        if (categoryIds != null && !categoryIds.isEmpty()) {
            query.setParameter("categoryIds", categoryIds);
        }
        if (searchPattern != null) {
            query.setParameter("searchPattern", searchPattern);
        }
    }
}
