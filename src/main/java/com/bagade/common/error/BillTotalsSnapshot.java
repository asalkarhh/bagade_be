package com.bagade.common.error;

public record BillTotalsSnapshot(
        String subtotal,
        String discountTotal,
        String total
) {}
