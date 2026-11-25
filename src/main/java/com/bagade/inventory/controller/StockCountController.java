package com.bagade.inventory.controller;

import com.bagade.auth.security.AppUserPrincipal;
import com.bagade.inventory.dto.CreateStockCountRequest;
import com.bagade.inventory.dto.CreateStockCountResponse;
import com.bagade.inventory.dto.PostStockCountRequest;
import com.bagade.inventory.dto.PostStockCountResponse;
import com.bagade.inventory.dto.SaveStockCountLineResponse;
import com.bagade.inventory.dto.StockCountDetailResponse;
import com.bagade.inventory.dto.UpdateStockCountLineRequest;
import com.bagade.inventory.service.StockCountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stock-counts")
@PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
public class StockCountController {

    private final StockCountService stockCountService;

    public StockCountController(StockCountService stockCountService) {
        this.stockCountService = stockCountService;
    }

    @PostMapping
    public ResponseEntity<CreateStockCountResponse> createStockCount(
            @Valid @RequestBody CreateStockCountRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        CreateStockCountResponse created = stockCountService.createStockCount(request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StockCountDetailResponse> getStockCount(@PathVariable UUID id) {
        return ResponseEntity.ok(stockCountService.getStockCount(id));
    }

    @PutMapping("/{id}/lines/{skuId}")
    public ResponseEntity<SaveStockCountLineResponse> saveLineCount(
            @PathVariable UUID id,
            @PathVariable UUID skuId,
            @Valid @RequestBody UpdateStockCountLineRequest request) {
        return ResponseEntity.ok(stockCountService.saveLineCount(id, skuId, request));
    }

    @PostMapping("/{id}/post")
    public ResponseEntity<PostStockCountResponse> postStockCount(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PostStockCountRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return ResponseEntity.ok(stockCountService.postStockCount(id, request, idempotencyKey, principal.getId()));
    }
}
