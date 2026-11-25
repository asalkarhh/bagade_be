package com.bagade.auth.config;

import com.bagade.auth.security.CustomAccessDeniedHandler;
import com.bagade.auth.security.CustomAuthenticationEntryPoint;
import com.bagade.auth.security.RequestIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final RequestIdFilter requestIdFilter;

    public SecurityConfig(
            CustomAuthenticationEntryPoint authenticationEntryPoint,
            CustomAccessDeniedHandler accessDeniedHandler,
            RequestIdFilter requestIdFilter) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.requestIdFilter = requestIdFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public HttpSessionCsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-Token");
        return repository;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            HttpSessionCsrfTokenRepository csrfTokenRepository) throws Exception {

        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        // Disabling deferral so the token value is immediately available on the request attribute
        requestHandler.setCsrfRequestAttributeName(null);

        http
                .addFilterBefore(requestIdFilter, SecurityContextHolderFilter.class)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(requestHandler)
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/session").permitAll()
                        .requestMatchers("/api/v1/reports/**", "/api/v1/purchase-planning/**", "/api/v1/test/owner-only").hasRole("OWNER")
                        .requestMatchers(
                                "/api/v1/operations/**",
                                "/api/v1/test/operational",
                                "/api/v1/categories/**",
                                "/api/v1/products/**",
                                "/api/v1/skus/**",
                                "/api/v1/vendors/**",
                                "/api/v1/purchase-orders/**",
                                "/api/v1/stock-receipts/**",
                                "/api/v1/inventory/**",
                                "/api/v1/barcodes/**",
                                "/api/v1/stock-batches/**",
                                "/api/v1/customers/**",
                                "/api/v1/bills/**"
                        ).hasAnyRole("OWNER", "WORKER")
                        .requestMatchers("/api/v1/auth/session").authenticated()
                        .anyRequest().authenticated()
                );

        return http.build();
    }
}
