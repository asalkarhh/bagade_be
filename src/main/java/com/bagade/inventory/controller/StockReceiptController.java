package com.bagade.inventory.controller;

import com.bagade.auth.security.AppUserPrincipal;
import com.bagade.inventory.dto.CreateStockReceiptRequest;
import com.bagade.inventory.dto.StockReceiptResponse;
import com.bagade.inventory.service.StockReceiptService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stock-receipts")
@PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
public class StockReceiptController {

    private final StockReceiptService stockReceiptService;

    public StockReceiptController(StockReceiptService stockReceiptService) {
        this.stockReceiptService = stockReceiptService;
    }

    @PostMapping
    public ResponseEntity<StockReceiptResponse> postReceipt(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateStockReceiptRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        StockReceiptResponse response = stockReceiptService.postReceipt(request, idempotencyKey, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StockReceiptResponse> getReceipt(@PathVariable UUID id) {
        return ResponseEntity.ok(stockReceiptService.getReceipt(id));
    }
}
