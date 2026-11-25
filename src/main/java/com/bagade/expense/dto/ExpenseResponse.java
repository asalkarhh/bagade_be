package com.bagade.expense.dto;

import com.bagade.billing.entity.PaymentMode;

import java.time.LocalDate;
import java.util.UUID;

public record ExpenseResponse(
        UUID expenseId,
        LocalDate expenseDate,
        String category,
        String amount,
        PaymentMode paymentMode,
        String notes,
        long version
) {}
