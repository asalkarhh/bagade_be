package com.bagade.auth;

import com.bagade.auth.entity.UserAccount;
import com.bagade.auth.entity.UserRole;
import com.bagade.auth.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UserAccount ownerUser;
    private UserAccount workerUser;
    private UserAccount inactiveUser;

    @BeforeEach
    void setUp() {
        userAccountRepository.deleteAll();

        ownerUser = userAccountRepository.save(new UserAccount(
                "owner1",
                "Shop Owner",
                passwordEncoder.encode("OwnerPass123!"),
                UserRole.OWNER,
                true
        ));

        workerUser = userAccountRepository.save(new UserAccount(
                "worker1",
                "Counter Staff",
                passwordEncoder.encode("WorkerPass123!"),
                UserRole.WORKER,
                true
        ));

        inactiveUser = userAccountRepository.save(new UserAccount(
                "inactive1",
                "Former Staff",
                passwordEncoder.encode("InactivePass123!"),
                UserRole.WORKER,
                false
        ));
    }

    private record CsrfContext(String token, MockHttpSession session) {}

    private CsrfContext fetchCsrf(MockHttpSession existingSession) throws Exception {
        var req = get("/api/v1/auth/csrf");
        if (existingSession != null) {
            req.session(existingSession);
        }
        MvcResult result = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.get("csrfToken").asText();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return new CsrfContext(token, session);
    }

    private MockHttpSession signIn(String username, String password) throws Exception {
        CsrfContext csrf = fetchCsrf(null);
        String oldSessionId = csrf.session().getId();

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "username", username,
                "password", password
        ));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/session")
                        .session(csrf.session())
                        .header("X-CSRF-Token", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession authenticatedSession = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(authenticatedSession).isNotNull();
        assertThat(authenticatedSession.getId()).isNotEqualTo(oldSessionId);
        return authenticatedSession;
    }

    @Test
    @DisplayName("Active owner can sign in, retrieve session, and access owner-only and operational routes")
    void ownerSignInAndAccess() throws Exception {
        MockHttpSession session = signIn("owner1", "OwnerPass123!");

        mockMvc.perform(get("/api/v1/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ownerUser.getId().toString()))
                .andExpect(jsonPath("$.name").value("Shop Owner"))
                .andExpect(jsonPath("$.role").value("OWNER"));

        mockMvc.perform(get("/api/v1/test/owner-only").session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/test/operational").session(session))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Active worker can sign in, access operational route, and gets 403 on owner-only route")
    void workerSignInAndRoleEnforcement() throws Exception {
        MockHttpSession session = signIn("worker1", "WorkerPass123!");

        mockMvc.perform(get("/api/v1/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(workerUser.getId().toString()))
                .andExpect(jsonPath("$.name").value("Counter Staff"))
                .andExpect(jsonPath("$.role").value("WORKER"));

        mockMvc.perform(get("/api/v1/test/operational").session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/test/owner-only").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("Invalid credentials and inactive accounts are rejected with 401 INVALID_CREDENTIALS")
    void invalidCredentialsAndInactiveAccountRejected() throws Exception {
        CsrfContext csrf = fetchCsrf(null);

        // Wrong password
        String wrongPasswordBody = objectMapper.writeValueAsString(java.util.Map.of(
                "username", "owner1",
                "password", "WrongPassword!"
        ));
        mockMvc.perform(post("/api/v1/auth/session")
                        .session(csrf.session())
                        .header("X-CSRF-Token", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongPasswordBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        // Unknown username
        String unknownUserBody = objectMapper.writeValueAsString(java.util.Map.of(
                "username", "nobody",
                "password", "SomePass123!"
        ));
        mockMvc.perform(post("/api/v1/auth/session")
                        .session(csrf.session())
                        .header("X-CSRF-Token", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownUserBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        // Inactive account
        String inactiveBody = objectMapper.writeValueAsString(java.util.Map.of(
                "username", inactiveUser.getUsername(),
                "password", "InactivePass123!"
        ));
        mockMvc.perform(post("/api/v1/auth/session")
                        .session(csrf.session())
                        .header("X-CSRF-Token", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inactiveBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("Validation errors return 400 with fieldErrors")
    void loginValidationReturnsFieldErrors() throws Exception {
        CsrfContext csrf = fetchCsrf(null);
        String emptyBody = objectMapper.writeValueAsString(java.util.Map.of(
                "username", "",
                "password", ""
        ));

        mockMvc.perform(post("/api/v1/auth/session")
                        .session(csrf.session())
                        .header("X-CSRF-Token", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
    }

    @Test
    @DisplayName("State-changing requests without valid CSRF token are rejected with 403")
    void missingOrInvalidCsrfRejected() throws Exception {
        CsrfContext csrf = fetchCsrf(null);
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "username", "owner1",
                "password", "OwnerPass123!"
        ));

        // Missing CSRF header on login
        mockMvc.perform(post("/api/v1/auth/session")
                        .session(csrf.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        // Invalid CSRF header on login
        mockMvc.perform(post("/api/v1/auth/session")
                        .session(csrf.session())
                        .header("X-CSRF-Token", "invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        // Missing CSRF header on logout
        MockHttpSession authSession = signIn("owner1", "OwnerPass123!");
        mockMvc.perform(delete("/api/v1/auth/session").session(authSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("Unauthenticated session lookup and protected routes return 401 AUTH_REQUIRED")
    void unauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        mockMvc.perform(get("/api/v1/test/owner-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("Signing out invalidates session, clears cookie, and subsequent lookup returns 401")
    void logoutInvalidatesSessionAndClearsCookie() throws Exception {
        MockHttpSession session = signIn("owner1", "OwnerPass123!");

        // Fetch fresh CSRF token after login
        CsrfContext postLoginCsrf = fetchCsrf(session);

        MvcResult logoutResult = mockMvc.perform(delete("/api/v1/auth/session")
                        .session(postLoginCsrf.session())
                        .header("X-CSRF-Token", postLoginCsrf.token()))
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie clearedCookie = logoutResult.getResponse().getCookie("JSESSIONID");
        assertThat(clearedCookie).isNotNull();
        assertThat(clearedCookie.getMaxAge()).isZero();
        assertThat(postLoginCsrf.session().isInvalid()).isTrue();

        // Subsequent session lookup without valid session returns 401
        mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        // Client can still fetch a fresh CSRF token after logout
        CsrfContext postLogoutCsrf = fetchCsrf(null);
        assertThat(postLogoutCsrf.token()).isNotBlank();
    }
}
