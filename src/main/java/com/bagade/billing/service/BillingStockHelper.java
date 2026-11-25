package com.bagade.billing.service;

import com.bagade.billing.dto.BillAllocationResponse;
import com.bagade.billing.dto.BillLineRequest;
import com.bagade.billing.dto.BillStockEffectResponse;
import com.bagade.billing.dto.SettlementEntryRequest;
import com.bagade.billing.entity.AllocationAttributionMethod;
import com.bagade.billing.entity.BillLine;
import com.bagade.billing.entity.BillRevision;
import com.bagade.billing.entity.BillStatus;
import com.bagade.billing.entity.BillingEvent;
import com.bagade.billing.entity.PaymentDirection;
import com.bagade.billing.entity.PaymentMode;
import com.bagade.billing.entity.SaleAllocation;
import com.bagade.billing.entity.SaleAllocationReversal;
import com.bagade.billing.repository.SaleAllocationRepository;
import com.bagade.billing.repository.SaleAllocationReversalRepository;
import com.bagade.catalog.entity.Sku;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.error.BatchSelectionRequiredException;
import com.bagade.common.error.FieldErrorItem;
import com.bagade.common.error.InsufficientStockException;
import com.bagade.common.error.RequestValidationException;
import com.bagade.common.error.ResourceNotFoundException;
import com.bagade.common.error.SelectableBatchDetail;
import com.bagade.inventory.dto.EligibleBatchResponse;
import com.bagade.inventory.entity.BarcodeMapping;
import com.bagade.inventory.entity.BarcodeSourceType;
import com.bagade.inventory.entity.BatchBalance;
import com.bagade.inventory.entity.MovementType;
import com.bagade.inventory.entity.StockBatch;
import com.bagade.inventory.entity.StockCondition;
import com.bagade.inventory.repository.BarcodeMappingRepository;
import com.bagade.inventory.repository.StockBatchRepository;
import com.bagade.inventory.service.InventoryMovementService;
import com.bagade.inventory.service.InventoryMovementService.BatchConditionKey;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class BillingStockHelper {

    private final SkuRepository skuRepository;
    private final StockBatchRepository stockBatchRepository;
    private final BarcodeMappingRepository barcodeMappingRepository;
    private final SaleAllocationRepository saleAllocationRepository;
    private final SaleAllocationReversalRepository saleAllocationReversalRepository;
    private final InventoryMovementService inventoryMovementService;

    public BillingStockHelper(
            SkuRepository skuRepository,
            StockBatchRepository stockBatchRepository,
            BarcodeMappingRepository barcodeMappingRepository,
            SaleAllocationRepository saleAllocationRepository,
            SaleAllocationReversalRepository saleAllocationReversalRepository,
            InventoryMovementService inventoryMovementService) {
        this.skuRepository = skuRepository;
        this.stockBatchRepository = stockBatchRepository;
        this.barcodeMappingRepository = barcodeMappingRepository;
        this.saleAllocationRepository = saleAllocationRepository;
        this.saleAllocationReversalRepository = saleAllocationReversalRepository;
        this.inventoryMovementService = inventoryMovementService;
    }

    public record ComputedLine(
            int index,
            BillLineRequest request,
            Sku sku,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal lineSubtotal,
            BigDecimal lineTotal
    ) {}

    public record PlannedAllocation(
            int lineIndex,
            BatchBalance balance,
            int quantity,
            AllocationAttributionMethod method
    ) {}

    public record ParsedSettlement(
            PaymentDirection direction,
            PaymentMode mode,
            BigDecimal amount,
            String reference
    ) {}

    public record ReturnCreditBreakdown(
            BigDecimal subtotalDeltaAbs,
            BigDecimal discountDeltaAbs,
            BigDecimal netAmountDeltaAbs
    ) {}

    public Map<UUID, Sku> loadSkusWithActiveCheck(
            List<BillLineRequest> lines,
            String fieldPrefix) {
        Set<UUID> skuIds = new LinkedHashSet<>();
        for (BillLineRequest line : lines) {
            skuIds.add(line.skuId());
        }
        Map<UUID, Sku> skusById = skuRepository.findAllByIdWithProduct(skuIds).stream()
                .collect(Collectors.toMap(Sku::getId, Function.identity()));

        List<FieldErrorItem> errors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            BillLineRequest line = lines.get(i);
            Sku sku = skusById.get(line.skuId());
            if (sku == null) {
                throw new ResourceNotFoundException("SKU not found: " + line.skuId());
            }
            if (!sku.isActive()) {
                errors.add(new FieldErrorItem(
                        fieldPrefix + "[" + i + "].skuId",
                        "SKU is inactive: " + sku.getSkuCode()
                ));
            }
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException("Validation failed", errors);
        }
        return skusById;
    }

    public List<ComputedLine> computeLines(
            List<BillLineRequest> lines,
            Map<UUID, Sku> skusById,
            String fieldPrefix) {
        List<ComputedLine> computed = new ArrayList<>(lines.size());
        List<FieldErrorItem> errors = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            BillLineRequest req = lines.get(i);
            Sku sku = skusById.get(req.skuId());
            BigDecimal unitPrice = req.unitPriceOverride() != null
                    ? CatalogValues.parsePrice(req.unitPriceOverride(), fieldPrefix + "[" + i + "].unitPriceOverride")
                    : sku.getDefaultSellingPrice().setScale(2, RoundingMode.HALF_UP);
            BigDecimal discount = req.discountAmount() != null
                    ? CatalogValues.parsePrice(req.discountAmount(), fieldPrefix + "[" + i + "].discountAmount")
                    : BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

            BigDecimal lineSubtotal = unitPrice.multiply(BigDecimal.valueOf(req.quantity()))
                    .setScale(2, RoundingMode.UNNECESSARY);
            if (discount.compareTo(lineSubtotal) > 0) {
                errors.add(new FieldErrorItem(
                        fieldPrefix + "[" + i + "].discountAmount",
                        "Discount amount cannot exceed line subtotal"
                ));
            } else {
                computed.add(new ComputedLine(
                        i,
                        req,
                        sku,
                        unitPrice,
                        discount,
                        lineSubtotal,
                        lineSubtotal.subtract(discount)
                ));
            }
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException("Validation failed", errors);
        }
        return computed;
    }

    public void validateExplicitBatches(
            List<ComputedLine> computedLines,
            List<BatchBalance> skuBalances,
            String fieldPrefix) {
        Map<UUID, UUID> skuByKnownBatchId = new HashMap<>();
        for (BatchBalance bb : skuBalances) {
            skuByKnownBatchId.put(bb.getBatch().getId(), bb.getBatch().getSku().getId());
        }

        Set<UUID> unknownBatchIds = new LinkedHashSet<>();
        for (ComputedLine cl : computedLines) {
            UUID batchId = cl.request().batchId();
            if (batchId != null) {
                UUID ownerSkuId = skuByKnownBatchId.get(batchId);
                if (ownerSkuId == null) {
                    unknownBatchIds.add(batchId);
                } else if (!Objects.equals(ownerSkuId, cl.sku().getId())) {
                    throw new RequestValidationException(
                            fieldPrefix + "[" + cl.index() + "].batchId",
                            "Batch does not belong to the requested SKU"
                    );
                }
            }
        }

        if (!unknownBatchIds.isEmpty()) {
            Map<UUID, StockBatch> extraBatches = stockBatchRepository.findAllById(unknownBatchIds).stream()
                    .collect(Collectors.toMap(StockBatch::getId, Function.identity()));
            for (ComputedLine cl : computedLines) {
                UUID batchId = cl.request().batchId();
                if (batchId != null && unknownBatchIds.contains(batchId)) {
                    StockBatch batch = extraBatches.get(batchId);
                    if (batch == null) {
                        throw new ResourceNotFoundException("Stock batch not found: " + batchId);
                    }
                    if (!Objects.equals(batch.getSku().getId(), cl.sku().getId())) {
                        throw new RequestValidationException(
                                fieldPrefix + "[" + cl.index() + "].batchId",
                                "Batch does not belong to the requested SKU"
                        );
                    }
                }
            }
        }
    }

    public Map<UUID, String> loadInternalBarcodes(List<BatchBalance> balances) {
        if (balances.isEmpty()) {
            return Map.of();
        }
        Set<UUID> batchIds = new LinkedHashSet<>();
        for (BatchBalance bb : balances) {
            batchIds.add(bb.getBatch().getId());
        }
        return barcodeMappingRepository.findByBatchIdInAndSourceType(batchIds, BarcodeSourceType.INTERNAL)
                .stream()
                .collect(Collectors.toMap(bm -> bm.getBatch().getId(), BarcodeMapping::getCode));
    }

    public List<BatchBalance> filterSellableBalances(List<BatchBalance> balances) {
        List<BatchBalance> sellable = new ArrayList<>();
        for (BatchBalance bb : balances) {
            if (bb.getStockCondition() == StockCondition.SELLABLE) {
                sellable.add(bb);
            }
        }
        return sellable;
    }

    public Map<BatchConditionKey, BatchBalance> indexBalancesByBatchCondition(List<BatchBalance> balances) {
        Map<BatchConditionKey, BatchBalance> map = new LinkedHashMap<>();
        for (BatchBalance bb : balances) {
            map.put(new BatchConditionKey(bb.getBatch().getId(), bb.getStockCondition()), bb);
        }
        return map;
    }

    public Map<UUID, List<BatchBalance>> groupBalancesBySku(List<BatchBalance> sellableBalances) {
        Map<UUID, List<BatchBalance>> map = new LinkedHashMap<>();
        for (BatchBalance bb : sellableBalances) {
            map.computeIfAbsent(bb.getBatch().getSku().getId(), ignored -> new ArrayList<>()).add(bb);
        }
        return map;
    }

    public Map<UUID, BatchBalance> indexBalancesByBatchId(List<BatchBalance> sellableBalances) {
        Map<UUID, BatchBalance> map = new LinkedHashMap<>();
        for (BatchBalance bb : sellableBalances) {
            map.put(bb.getBatch().getId(), bb);
        }
        return map;
    }

    public Map<UUID, Integer> initRemainingMap(List<BatchBalance> sellableBalances) {
        Map<UUID, Integer> map = new LinkedHashMap<>();
        for (BatchBalance bb : sellableBalances) {
            map.put(bb.getBatch().getId(), bb.getQuantity());
        }
        return map;
    }

    public List<EligibleBatchResponse> findDifferingMrpBatches(
            List<BatchBalance> skuBalances,
            Map<UUID, Integer> remainingByBatchId,
            Map<UUID, String> internalBarcodesByBatchId) {
        List<EligibleBatchResponse> sellableBatches = new ArrayList<>();
        Set<Optional<BigDecimal>> distinctMrps = new HashSet<>();

        for (BatchBalance bb : skuBalances) {
            StockBatch batch = bb.getBatch();
            int rem = remainingByBatchId.getOrDefault(batch.getId(), 0);
            if (rem > 0) {
                BigDecimal normalizedMrp = batch.getPrintedMrp() != null
                        ? batch.getPrintedMrp().setScale(2, RoundingMode.HALF_UP)
                        : null;
                distinctMrps.add(Optional.ofNullable(normalizedMrp));
                sellableBatches.add(new EligibleBatchResponse(
                        batch.getId(),
                        batch.getReceivedDate(),
                        CatalogValues.formatPrice(batch.getPrintedMrp()),
                        rem,
                        internalBarcodesByBatchId.get(batch.getId())
                ));
            }
        }

        return distinctMrps.size() > 1 ? sellableBatches : List.of();
    }

    public void checkDifferingMrpForImplicitLines(
            List<ComputedLine> computedLines,
            Map<UUID, List<BatchBalance>> balancesBySku,
            Map<UUID, Integer> remainingByBatchId,
            Map<UUID, String> internalBarcodesByBatchId) {
        List<SelectableBatchDetail> batchSelectionIssues = new ArrayList<>();
        Set<UUID> flaggedSkus = new HashSet<>();
        for (ComputedLine cl : computedLines) {
            if (cl.request().batchId() == null && flaggedSkus.add(cl.sku().getId())) {
                List<BatchBalance> skuBalances = balancesBySku.getOrDefault(cl.sku().getId(), List.of());
                List<EligibleBatchResponse> differing = findDifferingMrpBatches(
                        skuBalances, remainingByBatchId, internalBarcodesByBatchId
                );
                for (EligibleBatchResponse b : differing) {
                    batchSelectionIssues.add(new SelectableBatchDetail(
                            cl.sku().getId(),
                            b.batchId(),
                            b.receivedDate(),
                            b.printedMrp(),
                            b.sellableQuantity(),
                            b.internalBarcode()
                    ));
                }
            }
        }
        if (!batchSelectionIssues.isEmpty()) {
            throw new BatchSelectionRequiredException(
                    "Batch selection is required because sellable batches have differing printed MRPs",
                    batchSelectionIssues
            );
        }
    }

    public List<PlannedAllocation> allocateStock(
            List<ComputedLine> computedLines,
            Map<UUID, List<BatchBalance>> balancesBySku,
            Map<UUID, BatchBalance> balanceByBatchId,
            Map<UUID, Integer> remainingByBatchId) {

        List<PlannedAllocation> allocations = new ArrayList<>();

        // Allocate explicit-batch lines first so implicit FIFO lines never consume tagged batches
        for (ComputedLine cl : computedLines) {
            if (cl.request().batchId() != null) {
                BatchBalance bal = balanceByBatchId.get(cl.request().batchId());
                int available = bal != null ? remainingByBatchId.getOrDefault(bal.getBatch().getId(), 0) : 0;
                if (available < cl.request().quantity()) {
                    throw new InsufficientStockException(
                            "Insufficient sellable stock for batch " + cl.request().batchId()
                                    + " (requested " + cl.request().quantity() + ", available " + available + ")"
                    );
                }
                remainingByBatchId.put(bal.getBatch().getId(), available - cl.request().quantity());
                allocations.add(new PlannedAllocation(
                        cl.index(),
                        bal,
                        cl.request().quantity(),
                        AllocationAttributionMethod.EXPLICIT_BATCH
                ));
            }
        }

        for (ComputedLine cl : computedLines) {
            if (cl.request().batchId() == null) {
                List<BatchBalance> skuBalances = balancesBySku.getOrDefault(cl.sku().getId(), List.of());
                int needed = cl.request().quantity();
                int totalAvailable = 0;
                for (BatchBalance bb : skuBalances) {
                    totalAvailable += remainingByBatchId.getOrDefault(bb.getBatch().getId(), 0);
                }
                if (totalAvailable < needed) {
                    throw new InsufficientStockException(
                            "Insufficient sellable stock for SKU " + cl.sku().getSkuCode()
                                    + " (requested " + needed + ", available " + totalAvailable + ")"
                    );
                }
                for (BatchBalance bb : skuBalances) {
                    if (needed == 0) {
                        break;
                    }
                    UUID batchId = bb.getBatch().getId();
                    int rem = remainingByBatchId.getOrDefault(batchId, 0);
                    if (rem > 0) {
                        int take = Math.min(rem, needed);
                        remainingByBatchId.put(batchId, rem - take);
                        needed -= take;
                        allocations.add(new PlannedAllocation(
                                cl.index(),
                                bb,
                                take,
                                AllocationAttributionMethod.FIFO
                        ));
                    }
                }
            }
        }

        return allocations;
    }

    public List<BillAllocationResponse> persistPlannedAllocations(
            List<PlannedAllocation> plannedAllocations,
            Map<Integer, BillLine> savedBillLineByLineIndex,
            Instant postedAt,
            UUID actorUserId,
            List<BillStockEffectResponse> stockEffectsOut) {

        List<BillAllocationResponse> allocationResponses = new ArrayList<>(plannedAllocations.size());
        for (PlannedAllocation pa : plannedAllocations) {
            BillLine billLine = savedBillLineByLineIndex.get(pa.lineIndex());
            StockBatch batch = pa.balance().getBatch();

            SaleAllocation savedAlloc = saleAllocationRepository.saveAndFlush(new SaleAllocation(
                    billLine,
                    batch,
                    billLine.getSku(),
                    pa.quantity(),
                    batch.getUnitCost(),
                    pa.method()
            ));

            int resultingQty = inventoryMovementService.recordSaleDeduction(
                    pa.balance(),
                    pa.quantity(),
                    savedAlloc,
                    postedAt,
                    actorUserId
            );

            allocationResponses.add(new BillAllocationResponse(
                    billLine.getSku().getId(),
                    batch.getId(),
                    pa.quantity(),
                    CatalogValues.formatPrice(batch.getUnitCost()),
                    pa.method()
            ));

            if (stockEffectsOut != null) {
                stockEffectsOut.add(new BillStockEffectResponse(
                        billLine.getSku().getId(),
                        billLine.getSkuCodeSnapshot(),
                        batch.getId(),
                        StockCondition.SELLABLE,
                        MovementType.SALE,
                        -pa.quantity(),
                        resultingQty
                ));
            }
        }
        return allocationResponses;
    }

    public List<BillStockEffectResponse> reverseLogicalLineAllocations(
            List<SaleAllocation> lineAllocations,
            int quantityToReverse,
            StockCondition disposition,
            MovementType movementType,
            BillingEvent event,
            Map<BatchConditionKey, BatchBalance> lockedBalancesByKey,
            Instant occurredAt,
            UUID actorUserId,
            Set<UUID> touchedSkuIdsOut) {

        int remainingToReverse = quantityToReverse;
        List<BillStockEffectResponse> effects = new ArrayList<>();

        for (SaleAllocation sa : lineAllocations) {
            if (remainingToReverse == 0) {
                break;
            }
            int availableToReverse = sa.getRemainingQuantity();
            if (availableToReverse <= 0) {
                continue;
            }
            int take = Math.min(availableToReverse, remainingToReverse);
            remainingToReverse -= take;

            sa.recordReversal(take);
            SaleAllocationReversal reversal = saleAllocationReversalRepository.saveAndFlush(new SaleAllocationReversal(
                    sa,
                    event,
                    sa.getBatch(),
                    sa.getSku(),
                    take,
                    sa.getCapturedUnitCost(),
                    disposition,
                    occurredAt,
                    actorUserId
            ));

            int resultingQty = inventoryMovementService.recordSaleReversal(
                    sa.getBatch(),
                    disposition,
                    movementType,
                    take,
                    reversal,
                    lockedBalancesByKey,
                    occurredAt,
                    actorUserId
            );

            touchedSkuIdsOut.add(sa.getSku().getId());
            effects.add(new BillStockEffectResponse(
                    sa.getSku().getId(),
                    sa.getSku().getSkuCode(),
                    sa.getBatch().getId(),
                    disposition,
                    movementType,
                    take,
                    resultingQty
            ));
        }

        if (remainingToReverse != 0) {
            throw new IllegalStateException("Insufficient remaining allocation quantity to reverse");
        }
        return effects;
    }

    public ReturnCreditBreakdown computeReturnCredit(BillLine line, int prevReturnedQty, int returnQtyNow) {
        if (returnQtyNow <= 0 || prevReturnedQty + returnQtyNow > line.getQuantity()) {
            throw new IllegalArgumentException("Return quantity out of bounds for line " + line.getLogicalLineId());
        }
        int newCumQty = prevReturnedQty + returnQtyNow;
        BigDecimal totalQty = BigDecimal.valueOf(line.getQuantity());
        BigDecimal prevDiscCum = prevReturnedQty == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY)
                : line.getDiscountAmount()
                        .multiply(BigDecimal.valueOf(prevReturnedQty))
                        .divide(totalQty, 2, RoundingMode.HALF_UP);
        BigDecimal newDiscCum = newCumQty == line.getQuantity()
                ? line.getDiscountAmount()
                : line.getDiscountAmount()
                        .multiply(BigDecimal.valueOf(newCumQty))
                        .divide(totalQty, 2, RoundingMode.HALF_UP);

        BigDecimal discountDeltaAbs = newDiscCum.subtract(prevDiscCum);
        BigDecimal subtotalDeltaAbs = line.getUnitSellingPrice()
                .multiply(BigDecimal.valueOf(returnQtyNow))
                .setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal netDeltaAbs = subtotalDeltaAbs.subtract(discountDeltaAbs);
        return new ReturnCreditBreakdown(subtotalDeltaAbs, discountDeltaAbs, netDeltaAbs);
    }

    public List<ParsedSettlement> validateAndParseSettlement(
            List<SettlementEntryRequest> entries,
            String fieldPrefix) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<ParsedSettlement> parsed = new ArrayList<>(entries.size());
        List<FieldErrorItem> errors = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            SettlementEntryRequest req = entries.get(i);
            BigDecimal amount = CatalogValues.parsePrice(req.amount(), fieldPrefix + "[" + i + "].amount");
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(new FieldErrorItem(
                        fieldPrefix + "[" + i + "].amount",
                        "Settlement amount must be greater than 0.00"
                ));
            } else {
                parsed.add(new ParsedSettlement(
                        req.direction(),
                        req.mode(),
                        amount,
                        CatalogValues.normalizeOptionalText(req.reference())
                ));
            }
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException("Validation failed", errors);
        }
        return parsed;
    }

    public BillStatus deriveNonCancelledStatus(BillRevision revision) {
        long totalQty = 0;
        long totalReturned = 0;
        for (BillLine line : revision.getLines()) {
            totalQty += line.getQuantity();
            totalReturned += line.getReturnedQuantity();
        }
        if (totalReturned == 0) {
            return BillStatus.ISSUED;
        }
        if (totalReturned < totalQty) {
            return BillStatus.PARTIALLY_RETURNED;
        }
        return BillStatus.RETURNED;
    }

    public List<Map<String, Object>> canonicalizeBillLines(List<BillLineRequest> lines) {
        List<Map<String, Object>> list = new ArrayList<>(lines.size());
        for (BillLineRequest line : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", line.skuId().toString());
            m.put("quantity", line.quantity());
            m.put("batchId", line.batchId() != null ? line.batchId().toString() : null);
            m.put("discountAmount", line.discountAmount() != null ? line.discountAmount().trim() : null);
            m.put("unitPriceOverride", line.unitPriceOverride() != null ? line.unitPriceOverride().trim() : null);
            list.add(m);
        }
        return list;
    }

    public List<Map<String, Object>> canonicalizeSettlement(List<SettlementEntryRequest> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> list = new ArrayList<>(entries.size());
        for (SettlementEntryRequest e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("direction", e.direction().name());
            m.put("mode", e.mode().name());
            m.put("amount", e.amount() != null ? e.amount().trim() : null);
            m.put("reference", CatalogValues.normalizeOptionalText(e.reference()));
            list.add(m);
        }
        return list;
    }
}
