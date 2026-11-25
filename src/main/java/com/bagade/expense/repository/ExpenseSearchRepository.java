package com.bagade.expense.repository;

import com.bagade.expense.entity.Expense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

public interface ExpenseSearchRepository {

    Page<Expense> searchExpenses(LocalDate from, LocalDate to, String normalizedCategory, Pageable pageable);
}
