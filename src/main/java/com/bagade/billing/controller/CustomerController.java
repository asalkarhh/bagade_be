package com.bagade.billing.controller;

import com.bagade.auth.security.AppUserPrincipal;
import com.bagade.billing.dto.CreateCustomerRequest;
import com.bagade.billing.dto.CustomerResponse;
import com.bagade.billing.service.CustomerService;
import com.bagade.common.dto.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers")
@PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<CustomerResponse>> listCustomers(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(customerService.listCustomers(q, page, size));
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> createCustomer(
            @Valid @RequestBody CreateCustomerRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        CustomerResponse created = customerService.createCustomer(request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
