package com.bagade.billing.dto;

import com.bagade.billing.entity.BillStatus;

import java.util.UUID;

public record CancelBillResponse(
        UUID billId,
        long version,
        BillStatus status,
        String balanceDue
) {}
