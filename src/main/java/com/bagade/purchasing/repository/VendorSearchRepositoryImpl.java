package com.bagade.purchasing.repository;

import com.bagade.purchasing.dto.VendorResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class VendorSearchRepositoryImpl implements VendorSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<VendorResponse> searchVendors(String searchPattern, Boolean active, Pageable pageable) {
        List<String> predicates = new ArrayList<>();
        if (active != null) {
            predicates.add("v.active = :active");
        }
        if (searchPattern != null) {
            predicates.add("(LOWER(v.name) LIKE :searchPattern ESCAPE '!' OR (v.contact IS NOT NULL AND LOWER(v.contact) LIKE :searchPattern ESCAPE '!'))");
        }

        String whereClause = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);

        String countJpql = "SELECT COUNT(v.id) FROM Vendor v" + whereClause;
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
        bindParameters(countQuery, searchPattern, active);
        long total = countQuery.getSingleResult();

        if (total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        String selectJpql = """
                SELECT new com.bagade.purchasing.dto.VendorResponse(v.id, v.name, v.contact, v.active)
                FROM Vendor v
                """ + whereClause + " ORDER BY v.name ASC, v.id ASC";

        TypedQuery<VendorResponse> dataQuery = entityManager.createQuery(selectJpql, VendorResponse.class);
        bindParameters(dataQuery, searchPattern, active);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        return new PageImpl<>(dataQuery.getResultList(), pageable, total);
    }

    private void bindParameters(TypedQuery<?> query, String searchPattern, Boolean active) {
        if (active != null) {
            query.setParameter("active", active);
        }
        if (searchPattern != null) {
            query.setParameter("searchPattern", searchPattern);
        }
    }
}
