package com.bagade.inventory.controller;

import com.bagade.auth.security.AppUserPrincipal;
import com.bagade.inventory.dto.BatchLabelResponse;
import com.bagade.inventory.service.BatchLabelService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stock-batches")
@PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
public class StockBatchController {

    private final BatchLabelService batchLabelService;

    public StockBatchController(BatchLabelService batchLabelService) {
        this.batchLabelService = batchLabelService;
    }

    @PostMapping("/{id}/label")
    public ResponseEntity<BatchLabelResponse> generateOrGetLabel(
            @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return ResponseEntity.ok(batchLabelService.generateOrGetLabel(id, principal.getId()));
    }

    @GetMapping("/{id}/label")
    public ResponseEntity<BatchLabelResponse> getLabel(@PathVariable UUID id) {
        return ResponseEntity.ok(batchLabelService.getLabel(id));
    }
}
