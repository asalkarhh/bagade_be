package com.bagade.expense.dto;

import com.bagade.billing.entity.PaymentMode;
import com.bagade.catalog.validation.ValidInrPrice;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateExpenseRequest(
        @NotNull(message = "Expected version is required")
        @Min(value = 0, message = "Expected version must be 0 or greater")
        Long expectedVersion,

        LocalDate expenseDate,

        @Size(max = 100, message = "Category must not exceed 100 characters")
        String category,

        @ValidInrPrice(allowNull = true)
        String amount,

        PaymentMode paymentMode,

        @Size(max = 500, message = "Notes must not exceed 500 characters")
        String notes
) {}
