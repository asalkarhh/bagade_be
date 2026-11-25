package com.bagade.auth.security;

import com.bagade.auth.entity.UserAccount;
import com.bagade.auth.entity.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class AppUserPrincipal implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String username;
    private final String displayName;
    private final String passwordHash;
    private final UserRole role;
    private final boolean active;
    private final List<GrantedAuthority> authorities;

    public AppUserPrincipal(UserAccount userAccount) {
        this.id = userAccount.getId();
        this.username = userAccount.getUsername();
        this.displayName = userAccount.getDisplayName();
        this.passwordHash = userAccount.getPasswordHash();
        this.role = userAccount.getRole();
        this.active = userAccount.isActive();
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + userAccount.getRole().name()));
    }

    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
