package com.bagade.billing.dto;

import java.util.UUID;

public record EditBillResponse(
        UUID billId,
        int revision,
        long version,
        String total,
        String adjustmentAmount,
        String balanceDue
) {}
