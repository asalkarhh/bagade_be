package com.bagade.auth;

import com.bagade.auth.config.FirstOwnerInitializer;
import com.bagade.auth.config.FirstOwnerProperties;
import com.bagade.auth.entity.UserAccount;
import com.bagade.auth.entity.UserRole;
import com.bagade.auth.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FirstOwnerProvisioningTest {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userAccountRepository.deleteAll();
    }

    @Test
    @DisplayName("Provisions the first owner from environment-supplied credentials idempotently")
    void provisionsFirstOwnerIdempotently() {
        FirstOwnerProperties properties = new FirstOwnerProperties();
        properties.setUsername("initial_owner");
        properties.setPassword("SecretOwnerPass123!");
        properties.setDisplayName("Main Shop Owner");

        FirstOwnerInitializer initializer = new FirstOwnerInitializer(
                userAccountRepository,
                passwordEncoder,
                properties
        );

        // First run: should provision account
        initializer.run(new DefaultApplicationArguments());

        UserAccount owner = userAccountRepository.findByUsername("initial_owner")
                .orElseThrow(() -> new AssertionError("Owner should be provisioned"));

        assertThat(owner.getDisplayName()).isEqualTo("Main Shop Owner");
        assertThat(owner.getRole()).isEqualTo(UserRole.OWNER);
        assertThat(owner.isActive()).isTrue();
        assertThat(owner.getPasswordHash()).isNotEqualTo("SecretOwnerPass123!");
        assertThat(passwordEncoder.matches("SecretOwnerPass123!", owner.getPasswordHash())).isTrue();

        long countAfterFirstRun = userAccountRepository.count();
        assertThat(countAfterFirstRun).isEqualTo(1);

        // Second run (idempotent): should skip provisioning and maintain exactly 1 owner
        initializer.run(new DefaultApplicationArguments());

        long countAfterSecondRun = userAccountRepository.count();
        assertThat(countAfterSecondRun).isEqualTo(1);
    }

    @Test
    @DisplayName("Skips provisioning if owner credentials are not provided")
    void skipsProvisioningWhenNoCredentialsSupplied() {
        FirstOwnerProperties emptyProperties = new FirstOwnerProperties();
        FirstOwnerInitializer initializer = new FirstOwnerInitializer(
                userAccountRepository,
                passwordEncoder,
                emptyProperties
        );

        initializer.run(new DefaultApplicationArguments());

        assertThat(userAccountRepository.count()).isEqualTo(0);
    }
}
