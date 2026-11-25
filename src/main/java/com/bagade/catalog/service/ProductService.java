package com.bagade.catalog.service;

import com.bagade.catalog.dto.CreateProductRequest;
import com.bagade.catalog.dto.ProductResponse;
import com.bagade.catalog.entity.Category;
import com.bagade.catalog.entity.Product;
import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.repository.ProductRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        String normalizedName = CatalogValues.normalizeRequiredText(request.name());
        String normalizedBrand = CatalogValues.normalizeOptionalText(request.brand());
        String normalizedDesign = CatalogValues.normalizeOptionalText(request.design());

        Product product = new Product(category, normalizedName, normalizedBrand, normalizedDesign);
        Product saved = productRepository.save(product);

        return new ProductResponse(saved.getId(), category.getId(), saved.getName());
    }
}
