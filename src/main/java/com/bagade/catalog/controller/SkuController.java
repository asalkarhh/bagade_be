package com.bagade.catalog.controller;

import com.bagade.catalog.dto.SkuListItemResponse;
import com.bagade.catalog.dto.SkuResponse;
import com.bagade.catalog.dto.UpdateSkuRequest;
import com.bagade.catalog.service.SkuService;
import com.bagade.common.dto.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/skus")
@PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
public class SkuController {

    private final SkuService skuService;

    public SkuController(SkuService skuService) {
        this.skuService = skuService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<SkuListItemResponse>> listSkus(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(skuService.listSkus(q, categoryId, active, page, size));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<SkuResponse> updateSku(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSkuRequest request) {
        return ResponseEntity.ok(skuService.updateSku(id, request));
    }
}
