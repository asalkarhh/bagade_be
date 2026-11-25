package com.bagade.billing.dto;

import java.util.UUID;

public record CustomerResponse(
        UUID customerId,
        String name,
        String contact
) {}
