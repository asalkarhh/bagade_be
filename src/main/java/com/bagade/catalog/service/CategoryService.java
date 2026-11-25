package com.bagade.catalog.service;

import com.bagade.catalog.dto.CategoryResponse;
import com.bagade.catalog.dto.CreateCategoryRequest;
import com.bagade.catalog.entity.Category;
import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.dto.PagedResponse;
import com.bagade.common.error.ResourceNotFoundException;
import com.bagade.common.util.PaginationHelper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CategoryService {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"));

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<CategoryResponse> listCategories(UUID parentId, int page, int size) {
        Pageable pageable = PaginationHelper.createPageable(page, size, DEFAULT_SORT);
        Page<CategoryResponse> pageData = (parentId != null)
                ? categoryRepository.findByParentId(parentId, pageable)
                : categoryRepository.findAllCategories(pageable);
        return PagedResponse.from(pageData);
    }

    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        Category parent = null;
        if (request.parentId() != null) {
            parent = categoryRepository.findById(request.parentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Parent category not found"));
        }

        String normalizedName = CatalogValues.normalizeRequiredText(request.name());
        Category category = new Category(normalizedName, parent);
        Category saved = categoryRepository.save(category);

        UUID savedParentId = saved.getParent() != null ? saved.getParent().getId() : null;
        return new CategoryResponse(saved.getId(), saved.getName(), savedParentId);
    }
}
