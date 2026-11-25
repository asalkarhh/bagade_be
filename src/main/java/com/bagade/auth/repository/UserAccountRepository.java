package com.bagade.auth.repository;

import com.bagade.auth.entity.UserAccount;
import com.bagade.auth.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findByUsernameIgnoreCase(String username);

    boolean existsByUsername(String username);

    long countByRole(UserRole role);
}
