package com.bagade.catalog.controller;

import com.bagade.catalog.dto.CreateProductRequest;
import com.bagade.catalog.dto.CreateSkuRequest;
import com.bagade.catalog.dto.CreateSkuResponse;
import com.bagade.catalog.dto.ProductResponse;
import com.bagade.catalog.service.ProductService;
import com.bagade.catalog.service.SkuService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
public class ProductController {

    private final ProductService productService;
    private final SkuService skuService;

    public ProductController(ProductService productService, SkuService skuService) {
        this.productService = productService;
        this.skuService = skuService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse created = productService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/skus")
    public ResponseEntity<CreateSkuResponse> createSku(
            @PathVariable UUID id,
            @Valid @RequestBody CreateSkuRequest request) {
        CreateSkuResponse created = skuService.createSku(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
