package com.bagade.billing.repository;

import com.bagade.billing.dto.CustomerResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class CustomerSearchRepositoryImpl implements CustomerSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<CustomerResponse> searchCustomers(String searchPattern, Pageable pageable) {
        String whereClause = searchPattern != null
                ? " WHERE (LOWER(c.name) LIKE :searchPattern ESCAPE '!' OR (c.contact IS NOT NULL AND LOWER(c.contact) LIKE :searchPattern ESCAPE '!'))"
                : "";

        String countJpql = "SELECT COUNT(c.id) FROM Customer c" + whereClause;
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
        if (searchPattern != null) {
            countQuery.setParameter("searchPattern", searchPattern);
        }
        long total = countQuery.getSingleResult();

        if (total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        String selectJpql = """
                SELECT new com.bagade.billing.dto.CustomerResponse(c.id, c.name, c.contact)
                FROM Customer c
                """ + whereClause + " ORDER BY c.name ASC, c.id ASC";

        TypedQuery<CustomerResponse> dataQuery = entityManager.createQuery(selectJpql, CustomerResponse.class);
        if (searchPattern != null) {
            dataQuery.setParameter("searchPattern", searchPattern);
        }
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        return new PageImpl<>(dataQuery.getResultList(), pageable, total);
    }
}
