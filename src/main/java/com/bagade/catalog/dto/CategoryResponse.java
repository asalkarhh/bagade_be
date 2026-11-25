package com.bagade.catalog.dto;

import java.util.UUID;

public record CategoryResponse(
        UUID categoryId,
        String name,
        UUID parentId
) {}
