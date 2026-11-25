package com.bagade.purchasing.controller;

import com.bagade.auth.security.AppUserPrincipal;
import com.bagade.common.dto.PagedResponse;
import com.bagade.purchasing.dto.CancelPurchaseOrderRemainderRequest;
import com.bagade.purchasing.dto.CreatePurchaseOrderRequest;
import com.bagade.purchasing.dto.PurchaseOrderListItemResponse;
import com.bagade.purchasing.dto.PurchaseOrderResponse;
import com.bagade.purchasing.entity.PurchaseOrderStatus;
import com.bagade.purchasing.service.PurchaseOrderService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/purchase-orders")
@PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<PurchaseOrderListItemResponse>> listPurchaseOrders(
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) PurchaseOrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(purchaseOrderService.listPurchaseOrders(vendorId, status, from, to, page, size));
    }

    @PostMapping
    public ResponseEntity<PurchaseOrderResponse> createPurchaseOrder(
            @Valid @RequestBody CreatePurchaseOrderRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        PurchaseOrderResponse created = purchaseOrderService.createPurchaseOrder(request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PurchaseOrderResponse> getPurchaseOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(purchaseOrderService.getPurchaseOrder(id));
    }

    @PostMapping("/{id}/cancel-remainder")
    public ResponseEntity<PurchaseOrderResponse> cancelRemainder(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CancelPurchaseOrderRemainderRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return ResponseEntity.ok(purchaseOrderService.cancelRemainder(id, idempotencyKey, request, principal.getId()));
    }
}
