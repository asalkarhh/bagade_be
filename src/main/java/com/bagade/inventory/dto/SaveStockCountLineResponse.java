package com.bagade.inventory.dto;

import java.util.UUID;

public record SaveStockCountLineResponse(
        UUID countId,
        long countVersion,
        StockCountLineDetailResponse line
) {}
