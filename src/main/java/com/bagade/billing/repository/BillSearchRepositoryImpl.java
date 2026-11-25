package com.bagade.billing.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class BillSearchRepositoryImpl implements BillSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<BillListRow> searchBills(
            String billNumberPattern,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            Pageable pageable) {

        List<String> predicates = new ArrayList<>();
        if (billNumberPattern != null) {
            predicates.add("LOWER(b.billNumber) LIKE :billNumberPattern ESCAPE '!'");
        }
        if (from != null) {
            predicates.add("b.issueDate >= :fromDate");
        }
        if (to != null) {
            predicates.add("b.issueDate <= :toDate");
        }
        if (customerId != null) {
            predicates.add("b.customer.id = :customerId");
        }

        String whereClause = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);

        String countJpql = "SELECT COUNT(b.id) FROM Bill b" + whereClause;
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
        bindParameters(countQuery, billNumberPattern, from, to, customerId);
        long total = countQuery.getSingleResult();

        if (total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        String selectJpql = """
                SELECT new com.bagade.billing.repository.BillListRow(
                    b.id, b.billNumber, b.issueDate, b.status, b.total, b.paid, b.balanceDue
                )
                FROM Bill b
                """ + whereClause + " ORDER BY b.issueDate DESC, b.issuedAt DESC, b.id DESC";

        TypedQuery<BillListRow> dataQuery = entityManager.createQuery(selectJpql, BillListRow.class);
        bindParameters(dataQuery, billNumberPattern, from, to, customerId);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        return new PageImpl<>(dataQuery.getResultList(), pageable, total);
    }

    private void bindParameters(
            TypedQuery<?> query,
            String billNumberPattern,
            LocalDate from,
            LocalDate to,
            UUID customerId) {
        if (billNumberPattern != null) {
            query.setParameter("billNumberPattern", billNumberPattern);
        }
        if (from != null) {
            query.setParameter("fromDate", from);
        }
        if (to != null) {
            query.setParameter("toDate", to);
        }
        if (customerId != null) {
            query.setParameter("customerId", customerId);
        }
    }
}
