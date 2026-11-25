package com.bagade.catalog.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class SkuSearchRepositoryImpl implements SkuSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<SkuQueryRow> searchSkus(
            String searchPattern,
            Collection<UUID> categoryIds,
            boolean active,
            Pageable pageable) {

        StringBuilder whereClause = new StringBuilder(" WHERE s.active = :active");

        if (categoryIds != null) {
            if (categoryIds.isEmpty()) {
                return new PageImpl<>(List.of(), pageable, 0);
            }
            whereClause.append(" AND p.category.id IN :categoryIds");
        }

        if (searchPattern != null) {
            whereClause.append(" AND (LOWER(p.name) LIKE :searchPattern ESCAPE '!' ")
                    .append("OR LOWER(s.skuCode) LIKE :searchPattern ESCAPE '!' ")
                    .append("OR (s.size IS NOT NULL AND LOWER(s.size) LIKE :searchPattern ESCAPE '!'))");
        }

        String selectJpql = """
                SELECT new com.bagade.catalog.repository.SkuQueryRow(
                    s.id, p.name, s.size, s.skuCode, s.defaultSellingPrice, s.active, s.version
                )
                FROM Sku s
                JOIN s.product p
                """ + whereClause + " ORDER BY p.name ASC, s.skuCode ASC, s.id ASC";

        String countJpql = "SELECT COUNT(s.id) FROM Sku s JOIN s.product p" + whereClause;

        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
        bindParameters(countQuery, searchPattern, categoryIds, active);
        long total = countQuery.getSingleResult();

        if (total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        TypedQuery<SkuQueryRow> dataQuery = entityManager.createQuery(selectJpql, SkuQueryRow.class);
        bindParameters(dataQuery, searchPattern, categoryIds, active);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        List<SkuQueryRow> rows = dataQuery.getResultList();
        return new PageImpl<>(rows, pageable, total);
    }

    private void bindParameters(
            TypedQuery<?> query,
            String searchPattern,
            Collection<UUID> categoryIds,
            boolean active) {
        query.setParameter("active", active);
        if (categoryIds != null && !categoryIds.isEmpty()) {
            query.setParameter("categoryIds", categoryIds);
        }
        if (searchPattern != null) {
            query.setParameter("searchPattern", searchPattern);
        }
    }
}
