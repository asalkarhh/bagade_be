package com.bagade.expense.repository;

import com.bagade.expense.entity.Expense;
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
import java.util.Locale;

@Repository
public class ExpenseSearchRepositoryImpl implements ExpenseSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<Expense> searchExpenses(LocalDate from, LocalDate to, String normalizedCategory, Pageable pageable) {
        List<String> predicates = new ArrayList<>();
        if (from != null) {
            predicates.add("e.expenseDate >= :fromDate");
        }
        if (to != null) {
            predicates.add("e.expenseDate <= :toDate");
        }
        if (normalizedCategory != null) {
            predicates.add("LOWER(e.category) = :categoryLower");
        }

        String whereClause = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);

        TypedQuery<Long> countQuery = entityManager.createQuery("SELECT COUNT(e.id) FROM Expense e" + whereClause, Long.class);
        bind(countQuery, from, to, normalizedCategory);
        long total = countQuery.getSingleResult();

        if (total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        TypedQuery<Expense> dataQuery = entityManager.createQuery(
                "SELECT e FROM Expense e" + whereClause + " ORDER BY e.expenseDate DESC, e.createdAt DESC, e.id DESC",
                Expense.class
        );
        bind(dataQuery, from, to, normalizedCategory);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        return new PageImpl<>(dataQuery.getResultList(), pageable, total);
    }

    private void bind(TypedQuery<?> query, LocalDate from, LocalDate to, String normalizedCategory) {
        if (from != null) {
            query.setParameter("fromDate", from);
        }
        if (to != null) {
            query.setParameter("toDate", to);
        }
        if (normalizedCategory != null) {
            query.setParameter("categoryLower", normalizedCategory.toLowerCase(Locale.ROOT));
        }
    }
}
