package com.bagade.billing.dto;

import java.util.List;

public record BillPreviewResponse(
        List<BillPreviewLineResponse> lines,
        String subtotal,
        String discountTotal,
        String total,
        boolean canCheckout
) {}
