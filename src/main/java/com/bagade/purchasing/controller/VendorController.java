package com.bagade.purchasing.controller;

import com.bagade.auth.security.AppUserPrincipal;
import com.bagade.common.dto.PagedResponse;
import com.bagade.purchasing.dto.CreateVendorRequest;
import com.bagade.purchasing.dto.VendorResponse;
import com.bagade.purchasing.service.VendorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vendors")
@PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
public class VendorController {

    private final VendorService vendorService;

    public VendorController(VendorService vendorService) {
        this.vendorService = vendorService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<VendorResponse>> listVendors(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(vendorService.listVendors(q, active, page, size));
    }

    @PostMapping
    public ResponseEntity<VendorResponse> createVendor(
            @Valid @RequestBody CreateVendorRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        VendorResponse created = vendorService.createVendor(request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
