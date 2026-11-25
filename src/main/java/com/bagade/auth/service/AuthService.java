package com.bagade.auth.service;

import com.bagade.auth.dto.AuthSessionResponse;
import com.bagade.auth.dto.LoginRequest;
import com.bagade.auth.entity.UserAccount;
import com.bagade.auth.exception.InvalidCredentialsException;
import com.bagade.auth.repository.UserAccountRepository;
import com.bagade.auth.security.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final HttpSessionCsrfTokenRepository csrfTokenRepository;

    public AuthService(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            HttpSessionCsrfTokenRepository csrfTokenRepository) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @Transactional(readOnly = true)
    public AuthSessionResponse login(LoginRequest loginRequest, HttpServletRequest request, HttpServletResponse response) {
        String username = loginRequest.username() != null ? loginRequest.username().trim() : "";
        String password = loginRequest.password();

        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        if (!user.isActive()) {
            throw new InvalidCredentialsException();
        }

        AppUserPrincipal principal = new AppUserPrincipal(user);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities()
        );

        // Rotate session ID to protect against session fixation attacks
        HttpSession session = request.getSession(true);
        request.changeSessionId();

        // Save security context into session
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        // Generate and store a fresh CSRF token upon successful authentication
        CsrfToken freshCsrfToken = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(freshCsrfToken, request, response);
        request.setAttribute(CsrfToken.class.getName(), freshCsrfToken);

        return new AuthSessionResponse(user.getId(), user.getDisplayName(), user.getRole().name());
    }
}
