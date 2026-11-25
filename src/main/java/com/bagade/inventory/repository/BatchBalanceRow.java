package com.bagade.inventory.repository;

import com.bagade.inventory.entity.StockCondition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BatchBalanceRow(
        UUID skuId,
        UUID batchId,
        LocalDate receivedDate,
        BigDecimal unitCost,
        BigDecimal printedMrp,
        StockCondition condition,
        int quantity,
        String internalBarcode
) {}
