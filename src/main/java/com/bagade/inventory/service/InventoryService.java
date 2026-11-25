package com.bagade.inventory.service;

import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.dto.PagedResponse;
import com.bagade.common.util.PaginationHelper;
import com.bagade.inventory.dto.InventoryBatchResponse;
import com.bagade.inventory.dto.InventoryItemResponse;
import com.bagade.inventory.entity.StockCondition;
import com.bagade.inventory.repository.BatchBalanceRepository;
import com.bagade.inventory.repository.BatchBalanceRow;
import com.bagade.inventory.repository.InventorySearchRepository;
import com.bagade.inventory.repository.InventorySkuRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryService {

    private final InventorySearchRepository inventorySearchRepository;
    private final BatchBalanceRepository batchBalanceRepository;
    private final CategoryRepository categoryRepository;

    public InventoryService(
            InventorySearchRepository inventorySearchRepository,
            BatchBalanceRepository batchBalanceRepository,
            CategoryRepository categoryRepository) {
        this.inventorySearchRepository = inventorySearchRepository;
        this.batchBalanceRepository = batchBalanceRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<InventoryItemResponse> getInventory(
            UUID skuId,
            UUID categoryId,
            String q,
            int page,
            int size) {
        Pageable pageable = PaginationHelper.createPageable(page, size, Sort.unsorted());
        String searchPattern = CatalogValues.buildLikePattern(q);
        List<UUID> categoryIds = (categoryId != null)
                ? categoryRepository.findDescendantCategoryIds(categoryId)
                : null;

        Page<InventorySkuRow> skuPage = inventorySearchRepository.searchInventorySkus(
                skuId, categoryIds, searchPattern, pageable
        );

        List<UUID> pageSkuIds = skuPage.getContent().stream().map(InventorySkuRow::skuId).toList();
        Map<UUID, List<InventoryBatchResponse>> batchesBySkuId = new LinkedHashMap<>();
        Map<UUID, Long> sellableTotals = new LinkedHashMap<>();
        Map<UUID, Long> nonSellableTotals = new LinkedHashMap<>();

        if (!pageSkuIds.isEmpty()) {
            List<BatchBalanceRow> balanceRows = batchBalanceRepository.findBatchBalancesForSkuIds(pageSkuIds);
            Map<UUID, BatchAccumulator> accumulatorsByBatch = new LinkedHashMap<>();

            for (BatchBalanceRow row : balanceRows) {
                BatchAccumulator acc = accumulatorsByBatch.computeIfAbsent(
                        row.batchId(),
                        ignored -> new BatchAccumulator(
                                row.skuId(),
                                row.batchId(),
                                row.receivedDate(),
                                row.unitCost(),
                                row.printedMrp(),
                                row.internalBarcode()
                        )
                );
                if (row.condition() == StockCondition.SELLABLE) {
                    acc.sellable += row.quantity();
                    sellableTotals.merge(row.skuId(), (long) row.quantity(), Long::sum);
                } else {
                    acc.nonSellable += row.quantity();
                    nonSellableTotals.merge(row.skuId(), (long) row.quantity(), Long::sum);
                }
            }

            for (BatchAccumulator acc : accumulatorsByBatch.values()) {
                batchesBySkuId.computeIfAbsent(acc.skuId, ignored -> new ArrayList<>())
                        .add(new InventoryBatchResponse(
                                acc.batchId,
                                acc.receivedDate,
                                CatalogValues.formatPrice(acc.unitCost),
                                CatalogValues.formatPrice(acc.printedMrp),
                                acc.sellable,
                                acc.nonSellable,
                                acc.internalBarcode
                        ));
            }
        }

        Page<InventoryItemResponse> mapped = skuPage.map(row -> new InventoryItemResponse(
                row.skuId(),
                row.skuCode(),
                row.productName(),
                row.size(),
                CatalogValues.formatPrice(row.defaultSellingPrice()),
                row.active(),
                sellableTotals.getOrDefault(row.skuId(), 0L),
                nonSellableTotals.getOrDefault(row.skuId(), 0L),
                row.stockVersion(),
                batchesBySkuId.getOrDefault(row.skuId(), List.of())
        ));

        return PagedResponse.from(mapped);
    }

    private static final class BatchAccumulator {
        final UUID skuId;
        final UUID batchId;
        final LocalDate receivedDate;
        final BigDecimal unitCost;
        final BigDecimal printedMrp;
        final String internalBarcode;
        int sellable;
        int nonSellable;

        BatchAccumulator(
                UUID skuId,
                UUID batchId,
                LocalDate receivedDate,
                BigDecimal unitCost,
                BigDecimal printedMrp,
                String internalBarcode) {
            this.skuId = skuId;
            this.batchId = batchId;
            this.receivedDate = receivedDate;
            this.unitCost = unitCost;
            this.printedMrp = printedMrp;
            this.internalBarcode = internalBarcode;
        }
    }
}
