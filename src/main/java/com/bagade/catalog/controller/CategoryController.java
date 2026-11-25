package com.bagade.catalog.controller;

import com.bagade.catalog.dto.CategoryResponse;
import com.bagade.catalog.dto.CreateCategoryRequest;
import com.bagade.catalog.service.CategoryService;
import com.bagade.common.dto.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/categories")
@PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<CategoryResponse>> listCategories(
            @RequestParam(required = false) UUID parentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(categoryService.listCategories(parentId, page, size));
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        CategoryResponse created = categoryService.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
