package com.bagade.auth.config;

import com.bagade.auth.entity.UserAccount;
import com.bagade.auth.entity.UserRole;
import com.bagade.auth.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class FirstOwnerInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstOwnerInitializer.class);

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final FirstOwnerProperties properties;

    public FirstOwnerInitializer(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            FirstOwnerProperties properties) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String username = properties.getUsername();
        String password = properties.getPassword();
        String displayName = properties.getDisplayName();

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            if (userAccountRepository.countByRole(UserRole.OWNER) == 0) {
                log.warn("No owner credentials supplied via APP_FIRST_OWNER_USERNAME / APP_FIRST_OWNER_PASSWORD and no owner account exists.");
            }
            return;
        }

        String trimmedUsername = username.trim();
        if (userAccountRepository.findByUsernameIgnoreCase(trimmedUsername).isPresent()) {
            log.info("Initial owner account '{}' already exists. Skipping provisioning.", trimmedUsername);
            return;
        }

        UserAccount owner = new UserAccount();
        owner.setUsername(trimmedUsername);
        owner.setDisplayName((displayName != null && !displayName.isBlank()) ? displayName.trim() : "Owner");
        owner.setPasswordHash(passwordEncoder.encode(password));
        owner.setRole(UserRole.OWNER);
        owner.setActive(true);
        owner.setCreatedAt(Instant.now());

        userAccountRepository.save(owner);
        log.info("Successfully provisioned initial owner account with username '{}'.", trimmedUsername);
    }
}
