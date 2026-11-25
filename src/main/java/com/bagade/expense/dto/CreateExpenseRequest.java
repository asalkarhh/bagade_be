package com.bagade.expense.dto;

import com.bagade.billing.entity.PaymentMode;
import com.bagade.catalog.validation.ValidInrPrice;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateExpenseRequest(
        @NotNull(message = "Expense date is required")
        LocalDate expenseDate,

        @NotBlank(message = "Expense category is required")
        @Size(max = 100, message = "Category must not exceed 100 characters")
        String category,

        @ValidInrPrice(allowNull = false)
        String amount,

        @NotNull(message = "Payment mode is required")
        PaymentMode paymentMode,

        @Size(max = 500, message = "Notes must not exceed 500 characters")
        String notes
) {}
