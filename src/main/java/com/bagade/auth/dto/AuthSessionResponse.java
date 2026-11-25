package com.bagade.auth.dto;

import java.util.UUID;

public record AuthSessionResponse(
        UUID userId,
        String name,
        String role
) {}
