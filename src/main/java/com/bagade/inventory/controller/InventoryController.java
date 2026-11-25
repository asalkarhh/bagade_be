package com.bagade.inventory.controller;

import com.bagade.common.dto.PagedResponse;
import com.bagade.inventory.dto.InventoryItemResponse;
import com.bagade.inventory.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
@PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<InventoryItemResponse>> getInventory(
            @RequestParam(required = false) UUID skuId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(inventoryService.getInventory(skuId, categoryId, q, page, size));
    }
}
