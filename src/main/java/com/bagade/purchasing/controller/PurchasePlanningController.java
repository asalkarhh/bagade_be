package com.bagade.purchasing.controller;

import com.bagade.auth.security.AppUserPrincipal;
import com.bagade.purchasing.dto.PurchasePlanningResponse;
import com.bagade.purchasing.dto.PurchaseTargetResponse;
import com.bagade.purchasing.dto.UpsertPurchaseTargetRequest;
import com.bagade.purchasing.service.PurchasePlanningService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('OWNER')")
public class PurchasePlanningController {

    private final PurchasePlanningService purchasePlanningService;

    public PurchasePlanningController(PurchasePlanningService purchasePlanningService) {
        this.purchasePlanningService = purchasePlanningService;
    }

    @PutMapping("/purchase-targets/{skuId}")
    public ResponseEntity<PurchaseTargetResponse> upsertPurchaseTarget(
            @PathVariable UUID skuId,
            @Valid @RequestBody UpsertPurchaseTargetRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return ResponseEntity.ok(purchasePlanningService.upsertTarget(skuId, request, principal.getId()));
    }

    @GetMapping("/purchase-planning")
    public ResponseEntity<PurchasePlanningResponse> getPurchasePlanning(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String minPrice,
            @RequestParam(required = false) String maxPrice,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) String planningPeriod,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        return ResponseEntity.ok(purchasePlanningService.getPurchasePlanning(
                categoryId, size, minPrice, maxPrice, vendorId, planningPeriod, page, pageSize
        ));
    }
}
