package com.bagade.auth.security;

import com.bagade.common.error.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public CustomAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String requestId = RequestIdFilter.getRequestId(request);

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String code;
        String message;

        if (accessDeniedException instanceof CsrfException) {
            code = "CSRF_ERROR";
            message = "Invalid or missing CSRF token";
        } else {
            code = "ACCESS_DENIED";
            message = "Access denied: insufficient permissions";
        }

        ApiErrorResponse error = new ApiErrorResponse(code, message, requestId);
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
