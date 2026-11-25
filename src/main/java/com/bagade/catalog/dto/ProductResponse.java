package com.bagade.catalog.dto;

import java.util.UUID;

public record ProductResponse(
        UUID productId,
        UUID categoryId,
        String name
) {}
