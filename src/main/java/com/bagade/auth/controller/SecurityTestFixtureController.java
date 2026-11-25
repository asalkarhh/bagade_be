package com.bagade.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/test")
public class SecurityTestFixtureController {

    @GetMapping("/owner-only")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Map<String, String>> ownerOnly() {
        return ResponseEntity.ok(Map.of("message", "Access granted to owner"));
    }

    @GetMapping("/operational")
    @PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
    public ResponseEntity<Map<String, String>> operational() {
        return ResponseEntity.ok(Map.of("message", "Access granted to shop operations"));
    }
}
