package com.bagade.catalog.service;

import com.bagade.catalog.dto.CreateSkuRequest;
import com.bagade.catalog.dto.CreateSkuResponse;
import com.bagade.catalog.dto.SkuListItemResponse;
import com.bagade.catalog.dto.SkuResponse;
import com.bagade.catalog.dto.UpdateSkuRequest;
import com.bagade.catalog.entity.Product;
import com.bagade.catalog.entity.Sku;
import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.repository.ProductRepository;
import com.bagade.catalog.repository.SkuQueryRow;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.dto.PagedResponse;
import com.bagade.common.error.DuplicateSkuException;
import com.bagade.common.error.FieldErrorItem;
import com.bagade.common.error.GlobalExceptionHandler;
import com.bagade.common.error.RequestValidationException;
import com.bagade.common.error.ResourceNotFoundException;
import com.bagade.common.error.StaleVersionException;
import com.bagade.common.util.PaginationHelper;
import com.bagade.inventory.entity.SkuStockState;
import com.bagade.inventory.repository.BatchBalanceRepository;
import com.bagade.inventory.repository.SkuStockStateRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class SkuService {

    private final SkuRepository skuRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SkuStockStateRepository skuStockStateRepository;
    private final BatchBalanceRepository batchBalanceRepository;

    public SkuService(
            SkuRepository skuRepository,
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            SkuStockStateRepository skuStockStateRepository,
            BatchBalanceRepository batchBalanceRepository) {
        this.skuRepository = skuRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.skuStockStateRepository = skuStockStateRepository;
        this.batchBalanceRepository = batchBalanceRepository;
    }

    @Transactional
    public CreateSkuResponse createSku(UUID productId, CreateSkuRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        String normalizedSkuCode = CatalogValues.normalizeRequiredText(request.skuCode());
        String normalizedSize = CatalogValues.normalizeOptionalText(request.size());
        BigDecimal price = CatalogValues.parsePrice(request.defaultSellingPrice(), "defaultSellingPrice");

        Sku sku = new Sku(product, normalizedSkuCode, normalizedSize, price);
        Sku saved;
        try {
            saved = skuRepository.saveAndFlush(sku);
        } catch (DataIntegrityViolationException ex) {
            if (GlobalExceptionHandler.isSkuUniqueViolation(ex)) {
                throw new DuplicateSkuException("A SKU with this code or product-and-size combination already exists");
            }
            throw ex;
        }

        skuStockStateRepository.saveAndFlush(new SkuStockState(saved.getId()));

        return new CreateSkuResponse(
                saved.getId(),
                saved.getSkuCode(),
                saved.getSize(),
                CatalogValues.formatPrice(saved.getDefaultSellingPrice()),
                saved.getVersion()
        );
    }

    @Transactional(readOnly = true)
    public PagedResponse<SkuListItemResponse> listSkus(
            String q,
            UUID categoryId,
            Boolean active,
            int page,
            int size) {
        Pageable pageable = PaginationHelper.createPageable(page, size, Sort.unsorted());
        boolean effectiveActive = active != null ? active : true;
        String searchPattern = CatalogValues.buildLikePattern(q);

        List<UUID> categoryIds = (categoryId != null)
                ? categoryRepository.findDescendantCategoryIds(categoryId)
                : null;

        Page<SkuQueryRow> queryPage = skuRepository.searchSkus(
                searchPattern,
                categoryIds,
                effectiveActive,
                pageable
        );

        Map<UUID, Integer> sellableBySkuId = fetchSellableQuantities(
                queryPage.getContent().stream().map(SkuQueryRow::skuId).toList()
        );

        Page<SkuListItemResponse> mappedPage = queryPage.map(row -> new SkuListItemResponse(
                row.skuId(),
                row.productId(),
                row.productName(),
                row.size(),
                row.skuCode(),
                CatalogValues.formatPrice(row.defaultSellingPrice()),
                sellableBySkuId.getOrDefault(row.skuId(), 0),
                row.active(),
                row.version()
        ));

        return PagedResponse.from(mappedPage);
    }

    @Transactional
    public SkuResponse updateSku(UUID skuId, UpdateSkuRequest request) {
        if (request.defaultSellingPrice() == null && request.active() == null) {
            throw new RequestValidationException(
                    "Validation failed",
                    List.of(
                            new FieldErrorItem("defaultSellingPrice", "At least one of defaultSellingPrice or active must be provided"),
                            new FieldErrorItem("active", "At least one of defaultSellingPrice or active must be provided")
                    )
            );
        }

        Sku sku = skuRepository.findByIdWithProduct(skuId)
                .orElseThrow(() -> new ResourceNotFoundException("SKU not found"));

        if (!Objects.equals(sku.getVersion(), request.expectedVersion())) {
            throw new StaleVersionException("SKU was modified by another request");
        }

        if (request.defaultSellingPrice() != null) {
            sku.setDefaultSellingPrice(CatalogValues.parsePrice(request.defaultSellingPrice(), "defaultSellingPrice"));
        }

        if (request.active() != null) {
            sku.setActive(request.active());
        }

        Sku saved = skuRepository.saveAndFlush(sku);
        int sellableQuantity = fetchSellableQuantities(List.of(saved.getId())).getOrDefault(saved.getId(), 0);

        return new SkuResponse(
                saved.getId(),
                saved.getProduct().getId(),
                saved.getProduct().getName(),
                saved.getSkuCode(),
                saved.getSize(),
                CatalogValues.formatPrice(saved.getDefaultSellingPrice()),
                sellableQuantity,
                saved.isActive(),
                saved.getVersion()
        );
    }

    private Map<UUID, Integer> fetchSellableQuantities(List<UUID> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> map = new HashMap<>();
        for (Object[] row : batchBalanceRepository.sumSellableBySkuIds(skuIds)) {
            UUID id = (UUID) row[0];
            long qty = ((Number) row[1]).longValue();
            map.put(id, Math.toIntExact(qty));
        }
        return map;
    }
}
