package com.bagade.purchasing.dto;

import java.util.UUID;

public record VendorResponse(
        UUID vendorId,
        String name,
        String contact,
        boolean active
) {}
