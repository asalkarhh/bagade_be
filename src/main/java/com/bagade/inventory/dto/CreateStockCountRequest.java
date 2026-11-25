package com.bagade.inventory.dto;

import com.bagade.inventory.entity.StockCountScope;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateStockCountRequest(
        @NotNull(message = "Stock count scope is required")
        StockCountScope scope,

        UUID categoryId
) {}
