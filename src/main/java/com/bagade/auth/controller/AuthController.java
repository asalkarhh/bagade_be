package com.bagade.auth.controller;

import com.bagade.auth.dto.AuthSessionResponse;
import com.bagade.auth.dto.LoginRequest;
import com.bagade.auth.security.AppUserPrincipal;
import com.bagade.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final HttpSessionCsrfTokenRepository csrfTokenRepository;

    @Value("${server.servlet.session.cookie.secure:false}")
    private boolean sessionCookieSecure;

    public AuthController(AuthService authService, HttpSessionCsrfTokenRepository csrfTokenRepository) {
        this.authService = authService;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> getCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken csrfToken = null;
        Object tokenAttr = request.getAttribute(CsrfToken.class.getName());
        if (tokenAttr instanceof CsrfToken ct) {
            csrfToken = ct;
        } else if (tokenAttr instanceof Supplier<?> supplier) {
            Object supplied = supplier.get();
            if (supplied instanceof CsrfToken ct) {
                csrfToken = ct;
            }
        }

        if (csrfToken == null) {
            csrfToken = csrfTokenRepository.loadToken(request);
        }

        if (csrfToken == null) {
            csrfToken = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(csrfToken, request, response);
            request.setAttribute(CsrfToken.class.getName(), csrfToken);
        }

        return ResponseEntity.ok(Map.of("csrfToken", csrfToken.getToken()));
    }

    @PostMapping("/session")
    public ResponseEntity<AuthSessionResponse> createSession(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthSessionResponse sessionResponse = authService.login(loginRequest, request, response);
        return ResponseEntity.ok(sessionResponse);
    }

    @GetMapping("/session")
    public ResponseEntity<AuthSessionResponse> getSession(@AuthenticationPrincipal AppUserPrincipal principal) {
        if (principal == null) {
            // Should be caught by Spring Security authentication entry point,
            // but safeguard if somehow reached
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(new AuthSessionResponse(
                principal.getId(),
                principal.getDisplayName(),
                principal.getRole().name()
        ));
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> deleteSession(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();

        ResponseCookie cookie = ResponseCookie.from("JSESSIONID", "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .sameSite("Lax")
                .secure(sessionCookieSecure)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ResponseEntity.noContent().build();
    }
}
