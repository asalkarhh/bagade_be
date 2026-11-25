package com.bagade.billing.repository;

import com.bagade.billing.entity.BillStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BillListRow(
        UUID billId,
        String billNumber,
        LocalDate issueDate,
        BillStatus status,
        BigDecimal total,
        BigDecimal paid,
        BigDecimal balanceDue
) {}
