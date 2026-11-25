package com.bagade.expense.service;

import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.dto.PagedResponse;
import com.bagade.common.error.FieldErrorItem;
import com.bagade.common.error.RequestValidationException;
import com.bagade.common.error.ResourceNotFoundException;
import com.bagade.common.error.StaleVersionException;
import com.bagade.common.util.PaginationHelper;
import com.bagade.expense.dto.CreateExpenseRequest;
import com.bagade.expense.dto.ExpenseResponse;
import com.bagade.expense.dto.UpdateExpenseRequest;
import com.bagade.expense.entity.Expense;
import com.bagade.expense.repository.ExpenseRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ExpenseService {

    private final ExpenseRepository expenseRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public ExpenseService(ExpenseRepository expenseRepository) {
        this.expenseRepository = expenseRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<ExpenseResponse> listExpenses(
            LocalDate from,
            LocalDate to,
            String category,
            int page,
            int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new RequestValidationException(
                    "Validation failed",
                    List.of(
                            new FieldErrorItem("from", "'from' date must not be after 'to' date"),
                            new FieldErrorItem("to", "'to' date must not be before 'from' date")
                    )
            );
        }

        Pageable pageable = PaginationHelper.createPageable(page, size, Sort.unsorted());
        String normalizedCategory = CatalogValues.normalizeOptionalText(category);
        Page<Expense> result = expenseRepository.searchExpenses(from, to, normalizedCategory, pageable);
        return PagedResponse.from(result.map(this::toResponse));
    }

    @Transactional
    public ExpenseResponse createExpense(CreateExpenseRequest request, UUID actorUserId) {
        String category = CatalogValues.normalizeRequiredText(request.category());
        BigDecimal amount = parsePositiveAmount(request.amount());
        String notes = CatalogValues.normalizeOptionalText(request.notes());

        Expense expense = expenseRepository.saveAndFlush(new Expense(
                request.expenseDate(),
                category,
                amount,
                request.paymentMode(),
                notes,
                Instant.now(),
                actorUserId
        ));
        return toResponse(expense);
    }

    @Transactional
    public ExpenseResponse updateExpense(UUID expenseId, UpdateExpenseRequest request) {
        if (request.expenseDate() == null
                && request.category() == null
                && request.amount() == null
                && request.paymentMode() == null
                && request.notes() == null) {
            throw new RequestValidationException("expense", "At least one editable field must be provided");
        }

        String normalizedCategory = null;
        if (request.category() != null) {
            normalizedCategory = CatalogValues.normalizeOptionalText(request.category());
            if (normalizedCategory == null) {
                throw new RequestValidationException("category", "Expense category must not be blank");
            }
        }

        BigDecimal parsedAmount = null;
        if (request.amount() != null) {
            parsedAmount = parsePositiveAmount(request.amount());
        }

        Expense expense = expenseRepository.findByIdForUpdate(expenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));

        if (!Objects.equals(expense.getVersion(), request.expectedVersion())) {
            throw new StaleVersionException("Expense was modified by another request");
        }

        if (request.expenseDate() != null) {
            expense.setExpenseDate(request.expenseDate());
        }
        if (normalizedCategory != null) {
            expense.setCategory(normalizedCategory);
        }
        if (parsedAmount != null) {
            expense.setAmount(parsedAmount);
        }
        if (request.paymentMode() != null) {
            expense.setPaymentMode(request.paymentMode());
        }
        if (request.notes() != null) {
            expense.setNotes(CatalogValues.normalizeOptionalText(request.notes()));
        }

        expense.touchUpdatedAt(Instant.now());
        entityManager.flush();

        return toResponse(expense);
    }

    private BigDecimal parsePositiveAmount(String raw) {
        BigDecimal amount = CatalogValues.parsePrice(raw, "amount");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RequestValidationException("amount", "Expense amount must be greater than 0.00");
        }
        return amount;
    }

    private ExpenseResponse toResponse(Expense expense) {
        return new ExpenseResponse(
                expense.getId(),
                expense.getExpenseDate(),
                expense.getCategory(),
                CatalogValues.formatPrice(expense.getAmount()),
                expense.getPaymentMode(),
                expense.getNotes(),
                expense.getVersion()
        );
    }
}
