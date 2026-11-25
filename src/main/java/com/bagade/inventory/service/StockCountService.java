package com.bagade.inventory.service;

import com.bagade.catalog.entity.Category;
import com.bagade.catalog.entity.Sku;
import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.error.ChangedStockSkuDetail;
import com.bagade.common.error.FieldErrorItem;
import com.bagade.common.error.RequestValidationException;
import com.bagade.common.error.ResourceNotFoundException;
import com.bagade.common.error.StaleVersionException;
import com.bagade.common.error.StockChangedException;
import com.bagade.common.error.StockCountAlreadyPostedException;
import com.bagade.common.idempotency.IdempotencyService;
import com.bagade.inventory.dto.CreateStockCountRequest;
import com.bagade.inventory.dto.CreateStockCountResponse;
import com.bagade.inventory.dto.PostStockCountRequest;
import com.bagade.inventory.dto.PostStockCountResponse;
import com.bagade.inventory.dto.SaveStockCountLineResponse;
import com.bagade.inventory.dto.StockCountAdjustmentSummaryResponse;
import com.bagade.inventory.dto.StockCountDetailResponse;
import com.bagade.inventory.dto.StockCountLineDetailResponse;
import com.bagade.inventory.dto.StockCountSkuRefResponse;
import com.bagade.inventory.dto.UpdateStockCountLineRequest;
import com.bagade.inventory.entity.BatchBalance;
import com.bagade.inventory.entity.SkuStockState;
import com.bagade.inventory.entity.StockBatch;
import com.bagade.inventory.entity.StockCount;
import com.bagade.inventory.entity.StockCountLine;
import com.bagade.inventory.entity.StockCountScope;
import com.bagade.inventory.entity.StockCountStatus;
import com.bagade.inventory.repository.BatchBalanceRepository;
import com.bagade.inventory.repository.SkuStockStateRepository;
import com.bagade.inventory.repository.StockCountLineRepository;
import com.bagade.inventory.repository.StockCountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StockCountService {

    private static final String POST_STOCK_COUNT_OPERATION = "POST_STOCK_COUNT";

    private final StockCountRepository stockCountRepository;
    private final StockCountLineRepository stockCountLineRepository;
    private final CategoryRepository categoryRepository;
    private final SkuRepository skuRepository;
    private final SkuStockStateRepository skuStockStateRepository;
    private final BatchBalanceRepository batchBalanceRepository;
    private final InventoryMovementService inventoryMovementService;
    private final IdempotencyService idempotencyService;
    private final ZoneId shopZone;

    @PersistenceContext
    private EntityManager entityManager;

    public StockCountService(
            StockCountRepository stockCountRepository,
            StockCountLineRepository stockCountLineRepository,
            CategoryRepository categoryRepository,
            SkuRepository skuRepository,
            SkuStockStateRepository skuStockStateRepository,
            BatchBalanceRepository batchBalanceRepository,
            InventoryMovementService inventoryMovementService,
            IdempotencyService idempotencyService,
            @Value("${app.shop.timezone:Asia/Kolkata}") String shopTimezone) {
        this.stockCountRepository = stockCountRepository;
        this.stockCountLineRepository = stockCountLineRepository;
        this.categoryRepository = categoryRepository;
        this.skuRepository = skuRepository;
        this.skuStockStateRepository = skuStockStateRepository;
        this.batchBalanceRepository = batchBalanceRepository;
        this.inventoryMovementService = inventoryMovementService;
        this.idempotencyService = idempotencyService;
        this.shopZone = ZoneId.of(shopTimezone);
    }

    @Transactional
    public CreateStockCountResponse createStockCount(CreateStockCountRequest request, UUID actorUserId) {
        Category category = null;
        List<Sku> scopedSkus;

        if (request.scope() == StockCountScope.CATEGORY) {
            if (request.categoryId() == null) {
                throw new RequestValidationException("categoryId", "categoryId is required when scope is CATEGORY");
            }
            category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
            List<UUID> categoryIds = categoryRepository.findDescendantCategoryIds(request.categoryId());
            scopedSkus = categoryIds.isEmpty()
                    ? List.of()
                    : skuRepository.findCountableSkusForCategories(categoryIds);
        } else {
            if (request.categoryId() != null) {
                throw new RequestValidationException("categoryId", "categoryId must not be set when scope is SHOP");
            }
            scopedSkus = skuRepository.findCountableSkusForShop();
        }

        Instant now = Instant.now();
        StockCount count = new StockCount(request.scope(), category, now, actorUserId);
        List<StockCountSkuRefResponse> skuRefs = new ArrayList<>(scopedSkus.size());
        for (int i = 0; i < scopedSkus.size(); i++) {
            Sku sku = scopedSkus.get(i);
            count.addLine(new StockCountLine(i, sku, now));
            skuRefs.add(new StockCountSkuRefResponse(
                    sku.getId(),
                    sku.getSkuCode(),
                    sku.getProduct().getName(),
                    sku.getSize()
            ));
        }

        StockCount saved = stockCountRepository.saveAndFlush(count);
        return new CreateStockCountResponse(
                saved.getId(),
                saved.getScope(),
                saved.getCategory() != null ? saved.getCategory().getId() : null,
                saved.getStatus(),
                saved.getVersion(),
                skuRefs
        );
    }

    @Transactional(readOnly = true)
    public StockCountDetailResponse getStockCount(UUID countId) {
        StockCount count = stockCountRepository.findByIdWithDetails(countId)
                .orElseThrow(() -> new ResourceNotFoundException("Stock count not found"));

        List<StockCountLine> lines = count.getLines();
        List<UUID> skuIds = lines.stream().map(l -> l.getSku().getId()).toList();
        Map<UUID, Long> currentQtyBySku = loadCurrentSellableQuantities(skuIds);
        Map<UUID, Long> currentVersionBySku = loadCurrentStockVersions(skuIds);

        List<StockCountLineDetailResponse> lineResponses = lines.stream()
                .map(line -> toLineDetailResponse(
                        line,
                        currentQtyBySku.getOrDefault(line.getSku().getId(), 0L),
                        currentVersionBySku.getOrDefault(line.getSku().getId(), 0L)
                ))
                .toList();

        return new StockCountDetailResponse(
                count.getId(),
                count.getScope(),
                count.getCategory() != null ? count.getCategory().getId() : null,
                count.getStatus(),
                count.getVersion(),
                count.getCreatedAt(),
                count.getPostedAt(),
                count.getPostedBy(),
                lineResponses
        );
    }

    @Transactional
    public SaveStockCountLineResponse saveLineCount(
            UUID countId,
            UUID skuId,
            UpdateStockCountLineRequest request) {

        StockCount count = stockCountRepository.findByIdForUpdate(countId)
                .orElseThrow(() -> new ResourceNotFoundException("Stock count not found"));

        if (count.getStatus() == StockCountStatus.POSTED) {
            throw new StockCountAlreadyPostedException("Posted stock count is immutable");
        }
        if (!Objects.equals(count.getVersion(), request.expectedVersion())) {
            throw new StaleVersionException("Stock count was modified by another request");
        }

        StockCountLine line = stockCountLineRepository.findByStockCountIdAndSkuId(countId, skuId)
                .orElseThrow(() -> {
                    if (skuRepository.existsById(skuId)) {
                        return new RequestValidationException("skuId", "SKU is outside the scope of this stock count");
                    }
                    return new ResourceNotFoundException("SKU not found: " + skuId);
                });

        SkuStockState stockState = inventoryMovementService.lockOrInitializeStockStates(Set.of(skuId)).get(0);
        long currentStockVersion = stockState.getStockVersion();
        long currentSellableQty = loadCurrentSellableQuantities(List.of(skuId)).getOrDefault(skuId, 0L);

        if (!Objects.equals(request.observedStockVersion(), currentStockVersion)) {
            throw new StockChangedException(
                    "Stock changed for SKU " + line.getSku().getSkuCode() + " since version was observed",
                    List.of(new ChangedStockSkuDetail(
                            skuId,
                            line.getSku().getSkuCode(),
                            request.observedStockVersion(),
                            currentStockVersion,
                            currentSellableQty
                    ))
            );
        }

        String normalizedReason = CatalogValues.normalizeOptionalText(request.reason());
        Instant now = Instant.now();

        if (request.countedQuantity() == null) {
            line.updateCount(null, null, null, normalizedReason, now);
        } else {
            line.updateCount(
                    request.countedQuantity(),
                    Math.toIntExact(currentSellableQty),
                    currentStockVersion,
                    normalizedReason,
                    now
            );
        }

        count.touchUpdatedAt(now);
        entityManager.flush();

        return new SaveStockCountLineResponse(
                count.getId(),
                count.getVersion(),
                toLineDetailResponse(line, currentSellableQty, currentStockVersion)
        );
    }

    @Transactional
    public PostStockCountResponse postStockCount(
            UUID countId,
            PostStockCountRequest request,
            String rawIdempotencyKey,
            UUID actorUserId) {

        String idempotencyKey = idempotencyService.validateKey(rawIdempotencyKey);
        String requestHash = idempotencyService.computeRequestHash(
                buildCanonicalPostRequest(countId, request.expectedVersion())
        );

        var fastReplay = idempotencyService.findReplay(
                POST_STOCK_COUNT_OPERATION, idempotencyKey, requestHash, PostStockCountResponse.class);
        if (fastReplay.isPresent()) {
            return fastReplay.get().body();
        }

        StockCount count = stockCountRepository.findByIdForUpdate(countId)
                .orElseThrow(() -> new ResourceNotFoundException("Stock count not found"));

        var postLockReplay = idempotencyService.findReplay(
                POST_STOCK_COUNT_OPERATION, idempotencyKey, requestHash, PostStockCountResponse.class);
        if (postLockReplay.isPresent()) {
            return postLockReplay.get().body();
        }

        if (count.getStatus() == StockCountStatus.POSTED) {
            throw new StockCountAlreadyPostedException("Stock count is already posted");
        }
        if (!Objects.equals(count.getVersion(), request.expectedVersion())) {
            throw new StaleVersionException("Stock count was modified by another request");
        }

        List<StockCountLine> lines = stockCountLineRepository.findByStockCountIdWithSkuOrderByLineNumberAsc(countId);
        validateLinesForPosting(lines);

        Set<UUID> sortedSkuIds = new TreeSet<>();
        for (StockCountLine line : lines) {
            sortedSkuIds.add(line.getSku().getId());
        }

        // Acquire inventory locks in the established global order: SkuStockState (sorted skuId ASC) -> BatchBalance (FIFO)
        List<SkuStockState> lockedStockStates = inventoryMovementService.lockOrInitializeStockStates(sortedSkuIds);
        Map<UUID, SkuStockState> stateBySkuId = lockedStockStates.stream()
                .collect(Collectors.toMap(SkuStockState::getSkuId, Function.identity()));

        List<BatchBalance> lockedSellableBalances = sortedSkuIds.isEmpty()
                ? List.of()
                : batchBalanceRepository.findSellableBalancesForSkusForUpdate(sortedSkuIds);

        Map<UUID, List<BatchBalance>> balancesBySku = new LinkedHashMap<>();
        Map<UUID, Long> liveSellableBySku = new HashMap<>();
        for (BatchBalance bb : lockedSellableBalances) {
            UUID skuId = bb.getBatch().getSku().getId();
            balancesBySku.computeIfAbsent(skuId, ignored -> new ArrayList<>()).add(bb);
            liveSellableBySku.merge(skuId, (long) bb.getQuantity(), Long::sum);
        }

        // Verify all observed stock versions & expected quantities are still current under lock
        List<ChangedStockSkuDetail> changedSkus = new ArrayList<>();
        for (StockCountLine line : lines) {
            UUID skuId = line.getSku().getId();
            long currentVersion = stateBySkuId.get(skuId).getStockVersion();
            long liveQty = liveSellableBySku.getOrDefault(skuId, 0L);
            if (!Objects.equals(line.getObservedStockVersion(), currentVersion)
                    || line.getExpectedQuantity().longValue() != liveQty) {
                changedSkus.add(new ChangedStockSkuDetail(
                        skuId,
                        line.getSku().getSkuCode(),
                        line.getObservedStockVersion(),
                        currentVersion,
                        liveQty
                ));
            }
        }
        if (!changedSkus.isEmpty()) {
            throw new StockChangedException(
                    "Stock changed after physical count for one or more SKUs; recount affected SKUs before posting",
                    changedSkus
            );
        }

        Instant now = Instant.now();
        LocalDate auditDate = LocalDate.ofInstant(now, shopZone);
        List<SkuStockState> touchedStates = new ArrayList<>();
        List<StockCountAdjustmentSummaryResponse> summaries = new ArrayList<>(lines.size());

        for (StockCountLine line : lines) {
            Sku sku = line.getSku();
            int expected = line.getExpectedQuantity();
            int counted = line.getCountedQuantity();
            int diff = counted - expected;

            if (diff == 0) {
                summaries.add(new StockCountAdjustmentSummaryResponse(
                        sku.getId(),
                        sku.getSkuCode(),
                        sku.getProduct().getName(),
                        sku.getSize(),
                        expected,
                        counted,
                        0,
                        line.getReason(),
                        null,
                        List.of()
                ));
                continue;
            }

            List<UUID> affectedBatchIds = new ArrayList<>();
            String allocationBasis;

            if (diff < 0) {
                allocationBasis = "FIFO_SHORTAGE";
                int remainingShortage = -diff;
                List<BatchBalance> skuBalances = balancesBySku.getOrDefault(sku.getId(), List.of());
                for (BatchBalance bb : skuBalances) {
                    if (remainingShortage == 0) {
                        break;
                    }
                    int available = bb.getQuantity();
                    if (available > 0) {
                        int take = Math.min(available, remainingShortage);
                        remainingShortage -= take;
                        inventoryMovementService.recordAuditShortageDeduction(bb, take, line, now, actorUserId);
                        affectedBatchIds.add(bb.getBatch().getId());
                    }
                }
                if (remainingShortage != 0) {
                    throw new IllegalStateException("Insufficient sellable balance to deduct audit shortage for SKU " + sku.getSkuCode());
                }
            } else {
                allocationBasis = "AUDIT_SURPLUS";
                StockBatch surplusBatch = inventoryMovementService.recordAuditSurplus(
                        line, diff, auditDate, now, actorUserId
                );
                affectedBatchIds.add(surplusBatch.getId());
            }

            touchedStates.add(stateBySkuId.get(sku.getId()));
            summaries.add(new StockCountAdjustmentSummaryResponse(
                    sku.getId(),
                    sku.getSkuCode(),
                    sku.getProduct().getName(),
                    sku.getSize(),
                    expected,
                    counted,
                    diff,
                    line.getReason(),
                    allocationBasis,
                    affectedBatchIds
            ));
        }

        if (!touchedStates.isEmpty()) {
            inventoryMovementService.incrementStockVersions(touchedStates);
        }

        count.markPosted(now, actorUserId);
        entityManager.flush();

        PostStockCountResponse response = new PostStockCountResponse(
                count.getId(),
                count.getStatus(),
                count.getVersion(),
                count.getPostedAt(),
                summaries
        );

        idempotencyService.saveResult(
                POST_STOCK_COUNT_OPERATION, idempotencyKey, requestHash, 200, response, actorUserId
        );

        return response;
    }

    private void validateLinesForPosting(List<StockCountLine> lines) {
        List<FieldErrorItem> errors = new ArrayList<>();
        for (StockCountLine line : lines) {
            if (line.getCountedQuantity() == null
                    || line.getExpectedQuantity() == null
                    || line.getObservedStockVersion() == null) {
                errors.add(new FieldErrorItem(
                        "lines",
                        "Line is pending physical count for SKU " + line.getSku().getSkuCode()
                ));
            } else if (line.getCountedQuantity().intValue() != line.getExpectedQuantity().intValue()
                    && CatalogValues.normalizeOptionalText(line.getReason()) == null) {
                errors.add(new FieldErrorItem(
                        "lines",
                        "Reason is required for nonzero discrepancy on SKU " + line.getSku().getSkuCode()
                ));
            }
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException("Stock count is incomplete or missing discrepancy reasons", errors);
        }
    }

    private Map<UUID, Long> loadCurrentSellableQuantities(List<UUID> skuIds) {
        Map<UUID, Long> result = new HashMap<>();
        if (skuIds.isEmpty()) {
            return result;
        }
        for (Object[] row : batchBalanceRepository.sumSellableBySkuIds(skuIds)) {
            result.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    private Map<UUID, Long> loadCurrentStockVersions(List<UUID> skuIds) {
        Map<UUID, Long> result = new HashMap<>();
        if (skuIds.isEmpty()) {
            return result;
        }
        for (SkuStockState state : skuStockStateRepository.findBySkuIdIn(skuIds)) {
            result.put(state.getSkuId(), state.getStockVersion());
        }
        return result;
    }

    private StockCountLineDetailResponse toLineDetailResponse(
            StockCountLine line,
            long currentQuantity,
            long currentStockVersion) {
        Sku sku = line.getSku();
        return new StockCountLineDetailResponse(
                sku.getId(),
                sku.getSkuCode(),
                sku.getProduct().getName(),
                sku.getSize(),
                line.getCountedQuantity(),
                line.getExpectedQuantity(),
                currentQuantity,
                line.getObservedStockVersion(),
                currentStockVersion,
                line.getReason()
        );
    }

    private Map<String, Object> buildCanonicalPostRequest(UUID countId, Long expectedVersion) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("countId", countId.toString());
        canonical.put("expectedVersion", expectedVersion);
        return canonical;
    }
}
