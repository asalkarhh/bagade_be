package com.bagade.purchasing.dto;

import java.util.UUID;

public record PastVendorRefResponse(
        UUID vendorId,
        String vendorName,
        boolean active
) {}
