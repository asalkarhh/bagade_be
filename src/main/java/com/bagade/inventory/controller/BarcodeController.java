package com.bagade.inventory.controller;

import com.bagade.auth.security.AppUserPrincipal;
import com.bagade.inventory.dto.BarcodeLookupResponse;
import com.bagade.inventory.dto.BarcodeMappingResponse;
import com.bagade.inventory.dto.RegisterBarcodeRequest;
import com.bagade.inventory.service.BarcodeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/barcodes")
@PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
public class BarcodeController {

    private final BarcodeService barcodeService;

    public BarcodeController(BarcodeService barcodeService) {
        this.barcodeService = barcodeService;
    }

    @PostMapping
    public ResponseEntity<BarcodeMappingResponse> registerBrandBarcode(
            @Valid @RequestBody RegisterBarcodeRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        BarcodeService.RegistrationResult result = barcodeService.registerBrandBarcode(request, principal.getId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/lookup")
    public ResponseEntity<BarcodeLookupResponse> lookupBarcode(@RequestParam(required = false) String code) {
        return ResponseEntity.ok(barcodeService.lookupBarcode(code));
    }
}
