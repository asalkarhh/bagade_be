package com.bagade.billing.service;

import com.bagade.billing.dto.BillAllocationResponse;
import com.bagade.billing.dto.BillLineRequest;
import com.bagade.billing.dto.BillStockEffectResponse;
import com.bagade.billing.dto.CancelBillRequest;
import com.bagade.billing.dto.CancelBillResponse;
import com.bagade.billing.dto.CollectBillPaymentRequest;
import com.bagade.billing.dto.CollectBillPaymentResponse;
import com.bagade.billing.dto.CreateBillRevisionRequest;
import com.bagade.billing.dto.EditBillLineRequest;
import com.bagade.billing.dto.EditBillResponse;
import com.bagade.billing.dto.ExchangeBillRequest;
import com.bagade.billing.dto.ExchangeBillResponse;
import com.bagade.billing.dto.ReturnBillRequest;
import com.bagade.billing.dto.ReturnBillResponse;
import com.bagade.billing.dto.ReturnLineRequest;
import com.bagade.billing.dto.SettlementEntryRequest;
import com.bagade.billing.entity.Bill;
import com.bagade.billing.entity.BillLine;
import com.bagade.billing.entity.BillRevision;
import com.bagade.billing.entity.BillStatus;
import com.bagade.billing.entity.BillingEvent;
import com.bagade.billing.entity.BillingEventLine;
import com.bagade.billing.entity.BillingEventType;
import com.bagade.billing.entity.PaymentDirection;
import com.bagade.billing.entity.PaymentEntry;
import com.bagade.billing.entity.SaleAllocation;
import com.bagade.billing.entity.SalesAdjustment;
import com.bagade.billing.repository.BillRepository;
import com.bagade.billing.repository.BillRevisionRepository;
import com.bagade.billing.repository.BillingEventRepository;
import com.bagade.billing.repository.PaymentEntryRepository;
import com.bagade.billing.repository.SaleAllocationRepository;
import com.bagade.billing.repository.SalesAdjustmentRepository;
import com.bagade.billing.service.BillingStockHelper.ComputedLine;
import com.bagade.billing.service.BillingStockHelper.ParsedSettlement;
import com.bagade.billing.service.BillingStockHelper.PlannedAllocation;
import com.bagade.billing.service.BillingStockHelper.ReturnCreditBreakdown;
import com.bagade.catalog.entity.Sku;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.error.BillCancelledException;
import com.bagade.common.error.BillTotalsSnapshot;
import com.bagade.common.error.FieldErrorItem;
import com.bagade.common.error.PaymentExceedsBalanceException;
import com.bagade.common.error.PriceChangedException;
import com.bagade.common.error.RequestValidationException;
import com.bagade.common.error.ResourceNotFoundException;
import com.bagade.common.error.ReturnQuantityExceededException;
import com.bagade.common.error.StaleVersionException;
import com.bagade.common.idempotency.IdempotencyService;
import com.bagade.inventory.entity.BatchBalance;
import com.bagade.inventory.entity.MovementType;
import com.bagade.inventory.entity.SkuStockState;
import com.bagade.inventory.entity.StockCondition;
import com.bagade.inventory.repository.BatchBalanceRepository;
import com.bagade.inventory.service.InventoryMovementService;
import com.bagade.inventory.service.InventoryMovementService.BatchConditionKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BillCorrectionService {

    private static final String REVISE_BILL_OPERATION = "POST_BILL_REVISION";
    private static final String RETURN_BILL_OPERATION = "POST_BILL_RETURN";
    private static final String EXCHANGE_BILL_OPERATION = "POST_BILL_EXCHANGE";
    private static final String CANCEL_BILL_OPERATION = "POST_BILL_CANCEL";
    private static final String COLLECT_PAYMENT_OPERATION = "POST_BILL_PAYMENT";

    private final BillRepository billRepository;
    private final BillRevisionRepository billRevisionRepository;
    private final BillingEventRepository billingEventRepository;
    private final SaleAllocationRepository saleAllocationRepository;
    private final SalesAdjustmentRepository salesAdjustmentRepository;
    private final PaymentEntryRepository paymentEntryRepository;
    private final SkuRepository skuRepository;
    private final BatchBalanceRepository batchBalanceRepository;
    private final InventoryMovementService inventoryMovementService;
    private final BillingStockHelper stockHelper;
    private final IdempotencyService idempotencyService;

    @PersistenceContext
    private EntityManager entityManager;

    public BillCorrectionService(
            BillRepository billRepository,
            BillRevisionRepository billRevisionRepository,
            BillingEventRepository billingEventRepository,
            SaleAllocationRepository saleAllocationRepository,
            SalesAdjustmentRepository salesAdjustmentRepository,
            PaymentEntryRepository paymentEntryRepository,
            SkuRepository skuRepository,
            BatchBalanceRepository batchBalanceRepository,
            InventoryMovementService inventoryMovementService,
            BillingStockHelper stockHelper,
            IdempotencyService idempotencyService) {
        this.billRepository = billRepository;
        this.billRevisionRepository = billRevisionRepository;
        this.billingEventRepository = billingEventRepository;
        this.saleAllocationRepository = saleAllocationRepository;
        this.salesAdjustmentRepository = salesAdjustmentRepository;
        this.paymentEntryRepository = paymentEntryRepository;
        this.skuRepository = skuRepository;
        this.batchBalanceRepository = batchBalanceRepository;
        this.inventoryMovementService = inventoryMovementService;
        this.stockHelper = stockHelper;
        this.idempotencyService = idempotencyService;
    }

    private record NetSettlementResult(BigDecimal netPaid, BigDecimal balanceDue) {}

    private record PlannedEditLine(
            int requestIndex,
            UUID logicalLineId,
            BillLine prevLine,
            Sku sku,
            int newQuantity,
            int deltaQty,
            UUID batchId,
            String unitPriceOverride,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal lineSubtotal,
            BigDecimal lineTotal,
            BigDecimal activeGross,
            BigDecimal activeDiscount,
            BigDecimal activeNet
    ) {}

    @Transactional
    public EditBillResponse reviseBill(
            UUID billId,
            CreateBillRevisionRequest request,
            String rawIdempotencyKey,
            UUID actorUserId) {

        String idempotencyKey = idempotencyService.validateKey(rawIdempotencyKey);
        String reason = CatalogValues.normalizeRequiredText(request.reason());
        List<SettlementEntryRequest> settlementRequests = request.settlement() != null ? request.settlement() : List.of();
        String requestHash = idempotencyService.computeRequestHash(
                buildCanonicalRevisionRequest(billId, request, reason, settlementRequests)
        );

        var fastReplay = idempotencyService.findReplay(
                REVISE_BILL_OPERATION, idempotencyKey, requestHash, EditBillResponse.class);
        if (fastReplay.isPresent()) {
            return fastReplay.get().body();
        }

        List<ParsedSettlement> parsedSettlement = stockHelper.validateAndParseSettlement(settlementRequests, "settlement");
        BigDecimal expectedTotal = CatalogValues.parsePrice(request.expectedTotal(), "expectedTotal");

        Bill bill = billRepository.findByIdForUpdate(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        var postLockReplay = idempotencyService.findReplay(
                REVISE_BILL_OPERATION, idempotencyKey, requestHash, EditBillResponse.class);
        if (postLockReplay.isPresent()) {
            return postLockReplay.get().body();
        }

        ensureModifiableAndVersion(bill, request.expectedVersion());

        BillRevision prevRevision = billRevisionRepository
                .findByBillIdAndRevisionNumberWithLines(billId, bill.getCurrentRevisionNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Bill revision not found"));

        Map<UUID, BillLine> prevLinesByLogicalId = prevRevision.getLines().stream()
                .collect(Collectors.toMap(BillLine::getLogicalLineId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        Set<UUID> skuIdsToLoad = new LinkedHashSet<>();
        Set<UUID> seenLineIds = new HashSet<>();
        for (int i = 0; i < request.lines().size(); i++) {
            EditBillLineRequest req = request.lines().get(i);
            skuIdsToLoad.add(req.skuId());
            if (req.lineId() != null) {
                if (!seenLineIds.add(req.lineId())) {
                    throw new RequestValidationException("lines[" + i + "].lineId", "Duplicate lineId in revision lines");
                }
                if (!prevLinesByLogicalId.containsKey(req.lineId())) {
                    throw new ResourceNotFoundException("Bill line not found: " + req.lineId());
                }
            }
        }

        List<BillLine> removedLines = new ArrayList<>();
        for (BillLine prevLine : prevRevision.getLines()) {
            if (!seenLineIds.contains(prevLine.getLogicalLineId())) {
                if (prevLine.getReturnedQuantity() > 0) {
                    throw new RequestValidationException(
                            "lines",
                            "Cannot remove a bill line that already has returned units: " + prevLine.getLogicalLineId()
                    );
                }
                removedLines.add(prevLine);
                skuIdsToLoad.add(prevLine.getSku().getId());
            }
        }

        Map<UUID, Sku> skusById = skuRepository.findAllByIdWithProduct(skuIdsToLoad).stream()
                .collect(Collectors.toMap(Sku::getId, Function.identity()));

        List<PlannedEditLine> plannedLines = new ArrayList<>(request.lines().size());
        List<FieldErrorItem> lineErrors = new ArrayList<>();

        for (int i = 0; i < request.lines().size(); i++) {
            EditBillLineRequest req = request.lines().get(i);
            Sku sku = skusById.get(req.skuId());
            if (sku == null) {
                throw new ResourceNotFoundException("SKU not found: " + req.skuId());
            }
            BillLine prevLine = req.lineId() != null ? prevLinesByLogicalId.get(req.lineId()) : null;
            if (prevLine != null && !Objects.equals(prevLine.getSku().getId(), sku.getId())) {
                throw new RequestValidationException(
                        "lines[" + i + "].skuId",
                        "Cannot change SKU on an existing bill line; remove the line and add a new line instead"
                );
            }

            int prevQty = prevLine != null ? prevLine.getQuantity() : 0;
            int deltaQty = req.quantity() - prevQty;

            if (deltaQty > 0 && !sku.isActive()) {
                lineErrors.add(new FieldErrorItem("lines[" + i + "].skuId", "SKU is inactive: " + sku.getSkuCode()));
                continue;
            }
            if (prevLine != null && req.quantity() < prevLine.getReturnedQuantity()) {
                lineErrors.add(new FieldErrorItem(
                        "lines[" + i + "].quantity",
                        "Quantity cannot be less than already returned quantity (" + prevLine.getReturnedQuantity() + ")"
                ));
                continue;
            }

            BigDecimal unitPrice = req.unitPriceOverride() != null
                    ? CatalogValues.parsePrice(req.unitPriceOverride(), "lines[" + i + "].unitPriceOverride")
                    : (prevLine != null
                            ? prevLine.getUnitSellingPrice()
                            : sku.getDefaultSellingPrice().setScale(2, RoundingMode.HALF_UP));

            BigDecimal discount = req.discountAmount() != null
                    ? CatalogValues.parsePrice(req.discountAmount(), "lines[" + i + "].discountAmount")
                    : BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

            BigDecimal lineSubtotal = unitPrice.multiply(BigDecimal.valueOf(req.quantity()))
                    .setScale(2, RoundingMode.UNNECESSARY);
            if (discount.compareTo(lineSubtotal) > 0) {
                lineErrors.add(new FieldErrorItem(
                        "lines[" + i + "].discountAmount",
                        "Discount amount cannot exceed line subtotal"
                ));
                continue;
            }

            if (prevLine != null && prevLine.getReturnedQuantity() > 0) {
                if (unitPrice.compareTo(prevLine.getUnitSellingPrice()) != 0
                        || discount.compareTo(prevLine.getDiscountAmount()) != 0) {
                    lineErrors.add(new FieldErrorItem(
                            "lines[" + i + "]",
                            "Cannot modify price or discount on a line with already returned units"
                    ));
                    continue;
                }
            }

            BigDecimal lineTotal = lineSubtotal.subtract(discount);
            int returnedQty = prevLine != null ? prevLine.getReturnedQuantity() : 0;
            BigDecimal returnedDiscount = prevLine != null
                    ? prevLine.getReturnedDiscountAmount()
                    : BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
            BigDecimal returnedNet = prevLine != null
                    ? prevLine.getReturnedAmount()
                    : BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

            BigDecimal activeGross = unitPrice.multiply(BigDecimal.valueOf(req.quantity() - returnedQty))
                    .setScale(2, RoundingMode.UNNECESSARY);
            BigDecimal activeDiscount = discount.subtract(returnedDiscount);
            BigDecimal activeNet = lineTotal.subtract(returnedNet);

            plannedLines.add(new PlannedEditLine(
                    i,
                    prevLine != null ? prevLine.getLogicalLineId() : UUID.randomUUID(),
                    prevLine,
                    sku,
                    req.quantity(),
                    deltaQty,
                    req.batchId(),
                    req.unitPriceOverride(),
                    unitPrice,
                    discount,
                    lineSubtotal,
                    lineTotal,
                    activeGross,
                    activeDiscount,
                    activeNet
            ));
        }
        if (!lineErrors.isEmpty()) {
            throw new RequestValidationException("Validation failed", lineErrors);
        }

        BigDecimal newSubtotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal newDiscountTotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal newTotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (PlannedEditLine pl : plannedLines) {
            newSubtotal = newSubtotal.add(pl.activeGross());
            newDiscountTotal = newDiscountTotal.add(pl.activeDiscount());
            newTotal = newTotal.add(pl.activeNet());
        }

        if (newTotal.compareTo(expectedTotal) != 0) {
            throw new PriceChangedException(
                    "Bill total changed since preview; please review updated totals",
                    new BillTotalsSnapshot(
                            CatalogValues.formatPrice(newSubtotal),
                            CatalogValues.formatPrice(newDiscountTotal),
                            CatalogValues.formatPrice(newTotal)
                    )
            );
        }

        NetSettlementResult settlementResult = validateSettlement(bill, newTotal, parsedSettlement);

        // Lock affected SKU stock states & batch balances if stock changes occur (or lock union safely)
        Set<UUID> sortedStockSkuIds = new TreeSet<>(skuIdsToLoad);
        List<SkuStockState> lockedStockStates = inventoryMovementService.lockOrInitializeStockStates(sortedStockSkuIds);
        List<BatchBalance> lockedAllBalances = batchBalanceRepository.findAllBalancesForSkusForUpdate(sortedStockSkuIds);
        Map<BatchConditionKey, BatchBalance> balancesByConditionKey = stockHelper.indexBalancesByBatchCondition(lockedAllBalances);

        List<SaleAllocation> existingAllocations = saleAllocationRepository.findByBillIdOrdered(billId);
        Map<UUID, List<SaleAllocation>> allocationsByLogicalLine = existingAllocations.stream()
                .collect(Collectors.groupingBy(SaleAllocation::getLogicalLineId, LinkedHashMap::new, Collectors.toList()));

        Instant now = Instant.now();
        BigDecimal subtotalDelta = newSubtotal.subtract(bill.getSubtotal());
        BigDecimal discountDelta = newDiscountTotal.subtract(bill.getDiscountTotal());
        BigDecimal amountDelta = newTotal.subtract(bill.getTotal());

        BillRevision newRevision = new BillRevision(
                bill,
                bill.getCurrentRevisionNumber() + 1,
                newSubtotal,
                newDiscountTotal,
                newTotal,
                settlementResult.netPaid(),
                settlementResult.balanceDue(),
                now,
                actorUserId
        );

        for (int i = 0; i < plannedLines.size(); i++) {
            PlannedEditLine pl = plannedLines.get(i);
            BillLine prev = pl.prevLine();
            newRevision.addLine(new BillLine(
                    pl.logicalLineId(),
                    i,
                    pl.sku(),
                    prev != null ? prev.getSkuCodeSnapshot() : pl.sku().getSkuCode(),
                    prev != null ? prev.getProductNameSnapshot() : pl.sku().getProduct().getName(),
                    prev != null ? prev.getSizeSnapshot() : pl.sku().getSize(),
                    pl.newQuantity(),
                    prev != null ? prev.getReturnedQuantity() : 0,
                    pl.unitPrice(),
                    pl.discount(),
                    pl.lineTotal(),
                    prev != null ? prev.getReturnedDiscountAmount() : BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY),
                    prev != null ? prev.getReturnedAmount() : BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY)
            ));
        }
        BillRevision savedRevision = billRevisionRepository.saveAndFlush(newRevision);

        BillingEvent editEvent = new BillingEvent(
                bill, savedRevision, BillingEventType.EDIT, now, actorUserId, reason
        );
        for (BillLine removed : removedLines) {
            editEvent.addLine(new BillingEventLine(
                    removed.getLogicalLineId(),
                    removed.getSku(),
                    -removed.getQuantity(),
                    removed.getRemainingNetAmount().negate()
            ));
        }
        for (PlannedEditLine pl : plannedLines) {
            BigDecimal prevNet = pl.prevLine() != null
                    ? pl.prevLine().getRemainingNetAmount()
                    : BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
            BigDecimal lineDelta = pl.activeNet().subtract(prevNet);
            if (pl.prevLine() == null || pl.deltaQty() != 0 || lineDelta.compareTo(BigDecimal.ZERO) != 0) {
                editEvent.addLine(new BillingEventLine(
                        pl.logicalLineId(),
                        pl.sku(),
                        pl.deltaQty(),
                        lineDelta
                ));
            }
        }
        BillingEvent savedEvent = billingEventRepository.saveAndFlush(editEvent);

        salesAdjustmentRepository.save(new SalesAdjustment(
                bill,
                savedEvent,
                BillingEventType.EDIT,
                subtotalDelta,
                discountDelta,
                amountDelta,
                now,
                actorUserId
        ));

        // 1) Process allocation reversals first (removed lines + quantity reductions)
        Set<UUID> stockTouchedSkuIds = new HashSet<>();
        for (BillLine removed : removedLines) {
            stockHelper.reverseLogicalLineAllocations(
                    allocationsByLogicalLine.getOrDefault(removed.getLogicalLineId(), List.of()),
                    removed.getReturnableQuantity(),
                    StockCondition.SELLABLE,
                    MovementType.ADJUSTMENT,
                    savedEvent,
                    balancesByConditionKey,
                    now,
                    actorUserId,
                    stockTouchedSkuIds
            );
        }
        for (PlannedEditLine pl : plannedLines) {
            if (pl.deltaQty() < 0) {
                stockHelper.reverseLogicalLineAllocations(
                        allocationsByLogicalLine.getOrDefault(pl.logicalLineId(), List.of()),
                        -pl.deltaQty(),
                        StockCondition.SELLABLE,
                        MovementType.ADJUSTMENT,
                        savedEvent,
                        balancesByConditionKey,
                        now,
                        actorUserId,
                        stockTouchedSkuIds
                );
            }
        }

        // 2) Allocate additional stock for increased quantities & new lines using updated balances
        List<ComputedLine> linesNeedingStock = new ArrayList<>();
        Map<Integer, BillLine> savedBillLineByReqIndex = new HashMap<>();
        List<BillLine> savedRevisionLines = savedRevision.getLines();
        for (int i = 0; i < plannedLines.size(); i++) {
            PlannedEditLine pl = plannedLines.get(i);
            BillLine savedLine = savedRevisionLines.get(i);
            savedBillLineByReqIndex.put(pl.requestIndex(), savedLine);
            if (pl.deltaQty() > 0) {
                BillLineRequest allocReq = new BillLineRequest(
                        pl.sku().getId(),
                        pl.deltaQty(),
                        pl.batchId(),
                        null,
                        pl.unitPriceOverride()
                );
                linesNeedingStock.add(new ComputedLine(
                        pl.requestIndex(),
                        allocReq,
                        pl.sku(),
                        pl.unitPrice(),
                        BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2)
                ));
                stockTouchedSkuIds.add(pl.sku().getId());
            } else if (pl.batchId() != null) {
                // Validate explicit batch belongs to SKU even when no allocation occurs
                stockHelper.validateExplicitBatches(
                        List.of(new ComputedLine(
                                pl.requestIndex(),
                                new BillLineRequest(pl.sku().getId(), 1, pl.batchId(), null, null),
                                pl.sku(),
                                pl.unitPrice(),
                                BigDecimal.ZERO.setScale(2),
                                BigDecimal.ZERO.setScale(2),
                                BigDecimal.ZERO.setScale(2)
                        )),
                        stockHelper.filterSellableBalances(lockedAllBalances),
                        "lines"
                );
            }
        }

        if (!linesNeedingStock.isEmpty()) {
            List<BatchBalance> sellableBalances = stockHelper.filterSellableBalances(lockedAllBalances);
            stockHelper.validateExplicitBatches(linesNeedingStock, sellableBalances, "lines");
            Map<UUID, String> internalBarcodes = stockHelper.loadInternalBarcodes(sellableBalances);
            Map<UUID, List<BatchBalance>> balancesBySku = stockHelper.groupBalancesBySku(sellableBalances);
            Map<UUID, BatchBalance> balanceByBatchId = stockHelper.indexBalancesByBatchId(sellableBalances);
            Map<UUID, Integer> remainingByBatchId = stockHelper.initRemainingMap(sellableBalances);

            stockHelper.checkDifferingMrpForImplicitLines(
                    linesNeedingStock, balancesBySku, remainingByBatchId, internalBarcodes
            );

            List<PlannedAllocation> plannedAllocations = stockHelper.allocateStock(
                    linesNeedingStock, balancesBySku, balanceByBatchId, remainingByBatchId
            );
            stockHelper.persistPlannedAllocations(
                    plannedAllocations, savedBillLineByReqIndex, now, actorUserId, null
            );
        }

        persistSettlementEntries(bill, parsedSettlement, savedEvent, savedRevision, now, actorUserId);

        if (!stockTouchedSkuIds.isEmpty()) {
            List<SkuStockState> touchedStates = lockedStockStates.stream()
                    .filter(s -> stockTouchedSkuIds.contains(s.getSkuId()))
                    .toList();
            inventoryMovementService.incrementStockVersions(touchedStates);
        }

        bill.setCurrentRevisionNumber(savedRevision.getRevisionNumber());
        bill.setStatus(stockHelper.deriveNonCancelledStatus(savedRevision));
        bill.applyFinancialUpdate(
                newSubtotal, newDiscountTotal, newTotal, settlementResult.netPaid(), settlementResult.balanceDue(), now
        );

        entityManager.flush();

        EditBillResponse response = new EditBillResponse(
                bill.getId(),
                savedRevision.getRevisionNumber(),
                bill.getVersion(),
                CatalogValues.formatPrice(bill.getTotal()),
                CatalogValues.formatPrice(amountDelta),
                CatalogValues.formatPrice(bill.getBalanceDue())
        );

        idempotencyService.saveResult(
                REVISE_BILL_OPERATION, idempotencyKey, requestHash, 200, response, actorUserId
        );

        return response;
    }

    @Transactional
    public ReturnBillResponse returnItems(
            UUID billId,
            ReturnBillRequest request,
            String rawIdempotencyKey,
            UUID actorUserId) {

        String idempotencyKey = idempotencyService.validateKey(rawIdempotencyKey);
        String reason = CatalogValues.normalizeRequiredText(request.reason());
        List<SettlementEntryRequest> settlementRequests = request.settlement() != null ? request.settlement() : List.of();
        String requestHash = idempotencyService.computeRequestHash(
                buildCanonicalReturnRequest(billId, request.expectedVersion(), reason, request.returnedLines(), settlementRequests)
        );

        var fastReplay = idempotencyService.findReplay(
                RETURN_BILL_OPERATION, idempotencyKey, requestHash, ReturnBillResponse.class);
        if (fastReplay.isPresent()) {
            return fastReplay.get().body();
        }

        List<ParsedSettlement> parsedSettlement = stockHelper.validateAndParseSettlement(settlementRequests, "settlement");

        Bill bill = billRepository.findByIdForUpdate(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        var postLockReplay = idempotencyService.findReplay(
                RETURN_BILL_OPERATION, idempotencyKey, requestHash, ReturnBillResponse.class);
        if (postLockReplay.isPresent()) {
            return postLockReplay.get().body();
        }

        ensureModifiableAndVersion(bill, request.expectedVersion());

        BillRevision currentRevision = billRevisionRepository
                .findByBillIdAndRevisionNumberWithLines(billId, bill.getCurrentRevisionNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Bill revision not found"));

        Map<UUID, BillLine> linesByLogicalId = currentRevision.getLines().stream()
                .collect(Collectors.toMap(BillLine::getLogicalLineId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        Map<UUID, Integer> requestedQtyByLineId = validateReturnRequests(request.returnedLines(), linesByLogicalId);

        Map<UUID, Integer> simReturnedQty = new HashMap<>();
        for (UUID lineId : requestedQtyByLineId.keySet()) {
            simReturnedQty.put(lineId, linesByLogicalId.get(lineId).getReturnedQuantity());
        }

        List<ReturnCreditBreakdown> stepBreakdowns = new ArrayList<>(request.returnedLines().size());
        BigDecimal totalSubtotalDeltaAbs = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal totalDiscountDeltaAbs = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal totalNetDeltaAbs = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        Map<UUID, BigDecimal> netDeltaAbsByLogicalLine = new LinkedHashMap<>();
        Set<UUID> sortedReturnedSkuIds = new TreeSet<>();

        for (ReturnLineRequest req : request.returnedLines()) {
            BillLine line = linesByLogicalId.get(req.lineId());
            int curReturned = simReturnedQty.get(req.lineId());
            ReturnCreditBreakdown breakdown = stockHelper.computeReturnCredit(line, curReturned, req.quantity());
            simReturnedQty.put(req.lineId(), curReturned + req.quantity());
            stepBreakdowns.add(breakdown);

            totalSubtotalDeltaAbs = totalSubtotalDeltaAbs.add(breakdown.subtotalDeltaAbs());
            totalDiscountDeltaAbs = totalDiscountDeltaAbs.add(breakdown.discountDeltaAbs());
            totalNetDeltaAbs = totalNetDeltaAbs.add(breakdown.netAmountDeltaAbs());
            netDeltaAbsByLogicalLine.merge(req.lineId(), breakdown.netAmountDeltaAbs(), BigDecimal::add);
            sortedReturnedSkuIds.add(line.getSku().getId());
        }

        BigDecimal newSubtotal = bill.getSubtotal().subtract(totalSubtotalDeltaAbs);
        BigDecimal newDiscountTotal = bill.getDiscountTotal().subtract(totalDiscountDeltaAbs);
        BigDecimal newTotal = bill.getTotal().subtract(totalNetDeltaAbs);

        NetSettlementResult settlementResult = validateSettlement(bill, newTotal, parsedSettlement);

        List<SkuStockState> lockedStockStates = inventoryMovementService.lockOrInitializeStockStates(sortedReturnedSkuIds);
        List<BatchBalance> lockedBalances = batchBalanceRepository.findAllBalancesForSkusForUpdate(sortedReturnedSkuIds);
        Map<BatchConditionKey, BatchBalance> balancesByKey = stockHelper.indexBalancesByBatchCondition(lockedBalances);

        Map<UUID, List<SaleAllocation>> allocationsByLogicalLine = saleAllocationRepository
                .findByBillIdAndLogicalLineIdInOrdered(billId, requestedQtyByLineId.keySet())
                .stream()
                .collect(Collectors.groupingBy(SaleAllocation::getLogicalLineId, LinkedHashMap::new, Collectors.toList()));

        Instant now = Instant.now();
        BillingEvent returnEvent = new BillingEvent(
                bill, currentRevision, BillingEventType.RETURN, now, actorUserId, reason
        );
        for (Map.Entry<UUID, Integer> entry : requestedQtyByLineId.entrySet()) {
            BillLine line = linesByLogicalId.get(entry.getKey());
            returnEvent.addLine(new BillingEventLine(
                    entry.getKey(),
                    line.getSku(),
                    -entry.getValue(),
                    netDeltaAbsByLogicalLine.get(entry.getKey()).negate()
            ));
        }
        BillingEvent savedEvent = billingEventRepository.saveAndFlush(returnEvent);

        salesAdjustmentRepository.save(new SalesAdjustment(
                bill,
                savedEvent,
                BillingEventType.RETURN,
                totalSubtotalDeltaAbs.negate(),
                totalDiscountDeltaAbs.negate(),
                totalNetDeltaAbs.negate(),
                now,
                actorUserId
        ));

        List<BillStockEffectResponse> stockEffects = new ArrayList<>();
        Set<UUID> touchedSkuIds = new HashSet<>();
        for (int i = 0; i < request.returnedLines().size(); i++) {
            ReturnLineRequest req = request.returnedLines().get(i);
            ReturnCreditBreakdown breakdown = stepBreakdowns.get(i);
            BillLine line = linesByLogicalId.get(req.lineId());

            line.applyReturn(req.quantity(), breakdown.discountDeltaAbs(), breakdown.netAmountDeltaAbs());
            stockEffects.addAll(stockHelper.reverseLogicalLineAllocations(
                    allocationsByLogicalLine.getOrDefault(req.lineId(), List.of()),
                    req.quantity(),
                    req.disposition(),
                    MovementType.RETURN,
                    savedEvent,
                    balancesByKey,
                    now,
                    actorUserId,
                    touchedSkuIds
            ));
        }

        persistSettlementEntries(bill, parsedSettlement, savedEvent, null, now, actorUserId);

        inventoryMovementService.incrementStockVersions(lockedStockStates);
        bill.setStatus(stockHelper.deriveNonCancelledStatus(currentRevision));
        bill.applyFinancialUpdate(
                newSubtotal, newDiscountTotal, newTotal, settlementResult.netPaid(), settlementResult.balanceDue(), now
        );

        entityManager.flush();

        ReturnBillResponse response = new ReturnBillResponse(
                savedEvent.getId(),
                bill.getId(),
                bill.getVersion(),
                CatalogValues.formatPrice(bill.getBalanceDue()),
                stockEffects
        );

        idempotencyService.saveResult(
                RETURN_BILL_OPERATION, idempotencyKey, requestHash, 201, response, actorUserId
        );

        return response;
    }

    @Transactional
    public ExchangeBillResponse exchangeItems(
            UUID billId,
            ExchangeBillRequest request,
            String rawIdempotencyKey,
            UUID actorUserId) {

        String idempotencyKey = idempotencyService.validateKey(rawIdempotencyKey);
        String reason = CatalogValues.normalizeRequiredText(request.reason());
        List<SettlementEntryRequest> settlementRequests = request.settlement() != null ? request.settlement() : List.of();
        String requestHash = idempotencyService.computeRequestHash(
                buildCanonicalExchangeRequest(billId, request, reason, settlementRequests)
        );

        var fastReplay = idempotencyService.findReplay(
                EXCHANGE_BILL_OPERATION, idempotencyKey, requestHash, ExchangeBillResponse.class);
        if (fastReplay.isPresent()) {
            return fastReplay.get().body();
        }

        List<ParsedSettlement> parsedSettlement = stockHelper.validateAndParseSettlement(settlementRequests, "settlement");
        Map<UUID, Sku> replacementSkusById = stockHelper.loadSkusWithActiveCheck(request.replacementLines(), "replacementLines");
        List<ComputedLine> computedReplacements = stockHelper.computeLines(request.replacementLines(), replacementSkusById, "replacementLines");

        Bill bill = billRepository.findByIdForUpdate(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        var postLockReplay = idempotencyService.findReplay(
                EXCHANGE_BILL_OPERATION, idempotencyKey, requestHash, ExchangeBillResponse.class);
        if (postLockReplay.isPresent()) {
            return postLockReplay.get().body();
        }

        ensureModifiableAndVersion(bill, request.expectedVersion());

        BillRevision prevRevision = billRevisionRepository
                .findByBillIdAndRevisionNumberWithLines(billId, bill.getCurrentRevisionNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Bill revision not found"));

        Map<UUID, BillLine> prevLinesByLogicalId = prevRevision.getLines().stream()
                .collect(Collectors.toMap(BillLine::getLogicalLineId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        Map<UUID, Integer> requestedQtyByLineId = validateReturnRequests(request.returnedLines(), prevLinesByLogicalId);

        Map<UUID, Integer> simReturnedQty = new HashMap<>();
        for (UUID lineId : requestedQtyByLineId.keySet()) {
            simReturnedQty.put(lineId, prevLinesByLogicalId.get(lineId).getReturnedQuantity());
        }

        List<ReturnCreditBreakdown> stepBreakdowns = new ArrayList<>(request.returnedLines().size());
        BigDecimal retSubtotalAbs = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal retDiscountAbs = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal retNetAbs = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        Map<UUID, Integer> totalRetQtyByLine = new HashMap<>();
        Map<UUID, BigDecimal> totalRetDiscByLine = new HashMap<>();
        Map<UUID, BigDecimal> totalRetNetByLine = new LinkedHashMap<>();

        Set<UUID> sortedAllSkuIds = new TreeSet<>(replacementSkusById.keySet());
        for (int i = 0; i < request.returnedLines().size(); i++) {
            ReturnLineRequest req = request.returnedLines().get(i);
            BillLine line = prevLinesByLogicalId.get(req.lineId());
            int curReturned = simReturnedQty.get(req.lineId());
            ReturnCreditBreakdown breakdown = stockHelper.computeReturnCredit(line, curReturned, req.quantity());
            simReturnedQty.put(req.lineId(), curReturned + req.quantity());
            stepBreakdowns.add(breakdown);

            retSubtotalAbs = retSubtotalAbs.add(breakdown.subtotalDeltaAbs());
            retDiscountAbs = retDiscountAbs.add(breakdown.discountDeltaAbs());
            retNetAbs = retNetAbs.add(breakdown.netAmountDeltaAbs());

            totalRetQtyByLine.merge(req.lineId(), req.quantity(), Integer::sum);
            totalRetDiscByLine.merge(req.lineId(), breakdown.discountDeltaAbs(), BigDecimal::add);
            totalRetNetByLine.merge(req.lineId(), breakdown.netAmountDeltaAbs(), BigDecimal::add);
            sortedAllSkuIds.add(line.getSku().getId());
        }

        BigDecimal repSubtotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal repDiscount = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal repTotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (ComputedLine cl : computedReplacements) {
            repSubtotal = repSubtotal.add(cl.lineSubtotal());
            repDiscount = repDiscount.add(cl.discount());
            repTotal = repTotal.add(cl.lineTotal());
        }

        BigDecimal subtotalDelta = repSubtotal.subtract(retSubtotalAbs);
        BigDecimal discountDelta = repDiscount.subtract(retDiscountAbs);
        BigDecimal priceDifference = repTotal.subtract(retNetAbs);

        BigDecimal newSubtotal = bill.getSubtotal().add(subtotalDelta);
        BigDecimal newDiscountTotal = bill.getDiscountTotal().add(discountDelta);
        BigDecimal newTotal = bill.getTotal().add(priceDifference);

        NetSettlementResult settlementResult = validateSettlement(bill, newTotal, parsedSettlement);

        // Lock all affected SKUs in sorted order, then all batch balances
        List<SkuStockState> lockedStockStates = inventoryMovementService.lockOrInitializeStockStates(sortedAllSkuIds);
        List<BatchBalance> lockedAllBalances = batchBalanceRepository.findAllBalancesForSkusForUpdate(sortedAllSkuIds);
        Map<BatchConditionKey, BatchBalance> balancesByKey = stockHelper.indexBalancesByBatchCondition(lockedAllBalances);

        stockHelper.validateExplicitBatches(
                computedReplacements, stockHelper.filterSellableBalances(lockedAllBalances), "replacementLines"
        );

        Map<UUID, List<SaleAllocation>> allocationsByLogicalLine = saleAllocationRepository
                .findByBillIdAndLogicalLineIdInOrdered(billId, requestedQtyByLineId.keySet())
                .stream()
                .collect(Collectors.groupingBy(SaleAllocation::getLogicalLineId, LinkedHashMap::new, Collectors.toList()));

        Instant now = Instant.now();
        BillRevision newRevision = new BillRevision(
                bill,
                bill.getCurrentRevisionNumber() + 1,
                newSubtotal,
                newDiscountTotal,
                newTotal,
                settlementResult.netPaid(),
                settlementResult.balanceDue(),
                now,
                actorUserId
        );

        int lineNum = 0;
        for (BillLine prev : prevRevision.getLines()) {
            UUID lid = prev.getLogicalLineId();
            int addRetQty = totalRetQtyByLine.getOrDefault(lid, 0);
            BigDecimal addRetDisc = totalRetDiscByLine.getOrDefault(lid, BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY));
            BigDecimal addRetNet = totalRetNetByLine.getOrDefault(lid, BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY));
            newRevision.addLine(new BillLine(
                    lid,
                    lineNum++,
                    prev.getSku(),
                    prev.getSkuCodeSnapshot(),
                    prev.getProductNameSnapshot(),
                    prev.getSizeSnapshot(),
                    prev.getQuantity(),
                    prev.getReturnedQuantity() + addRetQty,
                    prev.getUnitSellingPrice(),
                    prev.getDiscountAmount(),
                    prev.getLineTotal(),
                    prev.getReturnedDiscountAmount().add(addRetDisc),
                    prev.getReturnedAmount().add(addRetNet)
            ));
        }

        List<UUID> replacementLogicalIds = new ArrayList<>(computedReplacements.size());
        Map<Integer, BillLine> savedReplacementLineByReqIndex = new HashMap<>();
        for (ComputedLine cl : computedReplacements) {
            UUID newLogicalId = UUID.randomUUID();
            replacementLogicalIds.add(newLogicalId);
            Sku sku = cl.sku();
            BillLine repLine = new BillLine(
                    newLogicalId,
                    lineNum++,
                    sku,
                    sku.getSkuCode(),
                    sku.getProduct().getName(),
                    sku.getSize(),
                    cl.request().quantity(),
                    cl.unitPrice(),
                    cl.discount(),
                    cl.lineTotal()
            );
            newRevision.addLine(repLine);
        }
        BillRevision savedRevision = billRevisionRepository.saveAndFlush(newRevision);

        int repStartIdx = prevRevision.getLines().size();
        List<BillLine> savedRevisionLines = savedRevision.getLines();
        for (int i = 0; i < computedReplacements.size(); i++) {
            savedReplacementLineByReqIndex.put(computedReplacements.get(i).index(), savedRevisionLines.get(repStartIdx + i));
        }

        BillingEvent exchangeEvent = new BillingEvent(
                bill, savedRevision, BillingEventType.EXCHANGE, now, actorUserId, reason
        );
        for (Map.Entry<UUID, Integer> entry : requestedQtyByLineId.entrySet()) {
            BillLine prev = prevLinesByLogicalId.get(entry.getKey());
            exchangeEvent.addLine(new BillingEventLine(
                    entry.getKey(),
                    prev.getSku(),
                    -entry.getValue(),
                    totalRetNetByLine.get(entry.getKey()).negate()
            ));
        }
        for (int i = 0; i < computedReplacements.size(); i++) {
            ComputedLine cl = computedReplacements.get(i);
            exchangeEvent.addLine(new BillingEventLine(
                    replacementLogicalIds.get(i),
                    cl.sku(),
                    cl.request().quantity(),
                    cl.lineTotal()
            ));
        }
        BillingEvent savedEvent = billingEventRepository.saveAndFlush(exchangeEvent);

        salesAdjustmentRepository.save(new SalesAdjustment(
                bill,
                savedEvent,
                BillingEventType.EXCHANGE,
                subtotalDelta,
                discountDelta,
                priceDifference,
                now,
                actorUserId
        ));

        // 1) Process returns first (updates locked BatchBalance entities)
        List<BillStockEffectResponse> stockEffects = new ArrayList<>();
        Set<UUID> touchedSkuIds = new HashSet<>();
        for (ReturnLineRequest req : request.returnedLines()) {
            stockEffects.addAll(stockHelper.reverseLogicalLineAllocations(
                    allocationsByLogicalLine.getOrDefault(req.lineId(), List.of()),
                    req.quantity(),
                    req.disposition(),
                    MovementType.RETURN,
                    savedEvent,
                    balancesByKey,
                    now,
                    actorUserId,
                    touchedSkuIds
            ));
        }

        // 2) Re-check differing MRP & allocate replacements against updated sellable balances (rolls back atomically if unavailable)
        List<BatchBalance> updatedSellableBalances = stockHelper.filterSellableBalances(lockedAllBalances);
        Map<UUID, String> internalBarcodes = stockHelper.loadInternalBarcodes(updatedSellableBalances);
        Map<UUID, List<BatchBalance>> balancesBySku = stockHelper.groupBalancesBySku(updatedSellableBalances);
        Map<UUID, BatchBalance> balanceByBatchId = stockHelper.indexBalancesByBatchId(updatedSellableBalances);
        Map<UUID, Integer> remainingByBatchId = stockHelper.initRemainingMap(updatedSellableBalances);

        stockHelper.checkDifferingMrpForImplicitLines(
                computedReplacements, balancesBySku, remainingByBatchId, internalBarcodes
        );
        List<PlannedAllocation> plannedAllocations = stockHelper.allocateStock(
                computedReplacements, balancesBySku, balanceByBatchId, remainingByBatchId
        );
        stockHelper.persistPlannedAllocations(
                plannedAllocations, savedReplacementLineByReqIndex, now, actorUserId, stockEffects
        );

        persistSettlementEntries(bill, parsedSettlement, savedEvent, savedRevision, now, actorUserId);

        inventoryMovementService.incrementStockVersions(lockedStockStates);
        bill.setCurrentRevisionNumber(savedRevision.getRevisionNumber());
        bill.setStatus(stockHelper.deriveNonCancelledStatus(savedRevision));
        bill.applyFinancialUpdate(
                newSubtotal, newDiscountTotal, newTotal, settlementResult.netPaid(), settlementResult.balanceDue(), now
        );

        entityManager.flush();

        ExchangeBillResponse response = new ExchangeBillResponse(
                savedEvent.getId(),
                bill.getId(),
                bill.getVersion(),
                CatalogValues.formatPrice(priceDifference),
                CatalogValues.formatPrice(bill.getBalanceDue()),
                stockEffects
        );

        idempotencyService.saveResult(
                EXCHANGE_BILL_OPERATION, idempotencyKey, requestHash, 201, response, actorUserId
        );

        return response;
    }

    @Transactional
    public CancelBillResponse cancelBill(
            UUID billId,
            CancelBillRequest request,
            String rawIdempotencyKey,
            UUID actorUserId) {

        String idempotencyKey = idempotencyService.validateKey(rawIdempotencyKey);
        String reason = CatalogValues.normalizeRequiredText(request.reason());
        List<SettlementEntryRequest> settlementRequests = request.settlement() != null ? request.settlement() : List.of();
        String requestHash = idempotencyService.computeRequestHash(
                buildCanonicalCancelRequest(billId, request.expectedVersion(), reason, settlementRequests)
        );

        var fastReplay = idempotencyService.findReplay(
                CANCEL_BILL_OPERATION, idempotencyKey, requestHash, CancelBillResponse.class);
        if (fastReplay.isPresent()) {
            return fastReplay.get().body();
        }

        List<ParsedSettlement> parsedSettlement = stockHelper.validateAndParseSettlement(settlementRequests, "settlement");

        Bill bill = billRepository.findByIdForUpdate(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        var postLockReplay = idempotencyService.findReplay(
                CANCEL_BILL_OPERATION, idempotencyKey, requestHash, CancelBillResponse.class);
        if (postLockReplay.isPresent()) {
            return postLockReplay.get().body();
        }

        ensureModifiableAndVersion(bill, request.expectedVersion());

        BigDecimal totalRefunds = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (int i = 0; i < parsedSettlement.size(); i++) {
            ParsedSettlement ps = parsedSettlement.get(i);
            if (ps.direction() == PaymentDirection.PAYMENT) {
                throw new RequestValidationException(
                        "settlement[" + i + "].direction",
                        "Payments are not allowed on bill cancellation"
                );
            }
            totalRefunds = totalRefunds.add(ps.amount());
        }

        if (bill.getPaid().compareTo(BigDecimal.ZERO) > 0) {
            if (totalRefunds.compareTo(bill.getPaid()) != 0) {
                throw new RequestValidationException(
                        "settlement",
                        "Cancellation requires refunding the remaining net collected amount of "
                                + CatalogValues.formatPrice(bill.getPaid())
                );
            }
        } else if (totalRefunds.compareTo(BigDecimal.ZERO) > 0) {
            throw new RequestValidationException("settlement", "No remaining net collection available to refund");
        }

        BillRevision currentRevision = billRevisionRepository
                .findByBillIdAndRevisionNumberWithLines(billId, bill.getCurrentRevisionNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Bill revision not found"));

        List<BillLine> unreturnedLines = new ArrayList<>();
        Set<UUID> sortedSkuIds = new TreeSet<>();
        Set<UUID> logicalLineIds = new LinkedHashSet<>();
        for (BillLine line : currentRevision.getLines()) {
            if (line.getReturnableQuantity() > 0) {
                unreturnedLines.add(line);
                sortedSkuIds.add(line.getSku().getId());
                logicalLineIds.add(line.getLogicalLineId());
            }
        }

        List<SkuStockState> lockedStockStates = inventoryMovementService.lockOrInitializeStockStates(sortedSkuIds);
        List<BatchBalance> lockedBalances = sortedSkuIds.isEmpty()
                ? List.of()
                : batchBalanceRepository.findAllBalancesForSkusForUpdate(sortedSkuIds);
        Map<BatchConditionKey, BatchBalance> balancesByKey = stockHelper.indexBalancesByBatchCondition(lockedBalances);

        Map<UUID, List<SaleAllocation>> allocationsByLogicalLine = logicalLineIds.isEmpty()
                ? Map.of()
                : saleAllocationRepository.findByBillIdAndLogicalLineIdInOrdered(billId, logicalLineIds)
                        .stream()
                        .collect(Collectors.groupingBy(SaleAllocation::getLogicalLineId, LinkedHashMap::new, Collectors.toList()));

        Instant now = Instant.now();
        BigDecimal subtotalDelta = bill.getSubtotal().negate();
        BigDecimal discountDelta = bill.getDiscountTotal().negate();
        BigDecimal amountDelta = bill.getTotal().negate();

        BillingEvent cancelEvent = new BillingEvent(
                bill, currentRevision, BillingEventType.CANCEL, now, actorUserId, reason
        );
        for (BillLine line : unreturnedLines) {
            int remQty = line.getReturnableQuantity();
            BigDecimal remNet = line.getRemainingNetAmount();
            cancelEvent.addLine(new BillingEventLine(
                    line.getLogicalLineId(),
                    line.getSku(),
                    -remQty,
                    remNet.negate()
            ));
        }
        BillingEvent savedEvent = billingEventRepository.saveAndFlush(cancelEvent);

        salesAdjustmentRepository.save(new SalesAdjustment(
                bill,
                savedEvent,
                BillingEventType.CANCEL,
                subtotalDelta,
                discountDelta,
                amountDelta,
                now,
                actorUserId
        ));

        Set<UUID> touchedSkuIds = new HashSet<>();
        for (BillLine line : unreturnedLines) {
            int remQty = line.getReturnableQuantity();
            BigDecimal remDisc = line.getRemainingDiscountAmount();
            BigDecimal remNet = line.getRemainingNetAmount();
            line.applyReturn(remQty, remDisc, remNet);

            stockHelper.reverseLogicalLineAllocations(
                    allocationsByLogicalLine.getOrDefault(line.getLogicalLineId(), List.of()),
                    remQty,
                    StockCondition.SELLABLE,
                    MovementType.ADJUSTMENT,
                    savedEvent,
                    balancesByKey,
                    now,
                    actorUserId,
                    touchedSkuIds
            );
        }

        persistSettlementEntries(bill, parsedSettlement, savedEvent, null, now, actorUserId);

        if (!lockedStockStates.isEmpty()) {
            inventoryMovementService.incrementStockVersions(lockedStockStates);
        }

        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        bill.setStatus(BillStatus.CANCELLED);
        bill.applyFinancialUpdate(zero, zero, zero, zero, zero, now);

        entityManager.flush();

        CancelBillResponse response = new CancelBillResponse(
                bill.getId(),
                bill.getVersion(),
                BillStatus.CANCELLED,
                CatalogValues.formatPrice(bill.getBalanceDue())
        );

        idempotencyService.saveResult(
                CANCEL_BILL_OPERATION, idempotencyKey, requestHash, 200, response, actorUserId
        );

        return response;
    }

    @Transactional
    public CollectBillPaymentResponse collectPayment(
            UUID billId,
            CollectBillPaymentRequest request,
            String rawIdempotencyKey,
            UUID actorUserId) {

        String idempotencyKey = idempotencyService.validateKey(rawIdempotencyKey);
        BigDecimal amount = CatalogValues.parsePrice(request.amount(), "amount");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RequestValidationException("amount", "Payment amount must be greater than 0.00");
        }
        String normalizedRef = CatalogValues.normalizeOptionalText(request.reference());
        String requestHash = idempotencyService.computeRequestHash(
                buildCanonicalPaymentRequest(billId, request.expectedVersion(), request.mode().name(), amount, normalizedRef)
        );

        var fastReplay = idempotencyService.findReplay(
                COLLECT_PAYMENT_OPERATION, idempotencyKey, requestHash, CollectBillPaymentResponse.class);
        if (fastReplay.isPresent()) {
            return fastReplay.get().body();
        }

        Bill bill = billRepository.findByIdForUpdate(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        var postLockReplay = idempotencyService.findReplay(
                COLLECT_PAYMENT_OPERATION, idempotencyKey, requestHash, CollectBillPaymentResponse.class);
        if (postLockReplay.isPresent()) {
            return postLockReplay.get().body();
        }

        ensureModifiableAndVersion(bill, request.expectedVersion());

        if (amount.compareTo(bill.getBalanceDue()) > 0) {
            throw new PaymentExceedsBalanceException(
                    "Payment amount " + CatalogValues.formatPrice(amount)
                            + " exceeds remaining bill balance due " + CatalogValues.formatPrice(bill.getBalanceDue())
            );
        }

        Instant now = Instant.now();
        BigDecimal newPaid = bill.getPaid().add(amount);
        BigDecimal newBalanceDue = bill.getBalanceDue().subtract(amount);

        // Link to currentRevision for historical reprint ONLY if bill total has not diverged via post-revision returns
        BillRevision currentRevision = billRevisionRepository
                .findByBillIdAndRevisionNumber(billId, bill.getCurrentRevisionNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Bill revision not found"));
        BillRevision linkedRevision = null;
        if (bill.getTotal().compareTo(currentRevision.getTotal()) == 0) {
            linkedRevision = currentRevision;
            currentRevision.updatePaymentSnapshot(newPaid, newBalanceDue);
        }

        PaymentEntry savedPayment = paymentEntryRepository.saveAndFlush(new PaymentEntry(
                bill,
                PaymentDirection.PAYMENT,
                request.mode(),
                amount,
                normalizedRef,
                null,
                linkedRevision,
                now,
                actorUserId
        ));

        bill.applyFinancialUpdate(
                bill.getSubtotal(),
                bill.getDiscountTotal(),
                bill.getTotal(),
                newPaid,
                newBalanceDue,
                now
        );

        entityManager.flush();

        CollectBillPaymentResponse response = new CollectBillPaymentResponse(
                savedPayment.getId(),
                bill.getId(),
                bill.getVersion(),
                CatalogValues.formatPrice(bill.getBalanceDue())
        );

        idempotencyService.saveResult(
                COLLECT_PAYMENT_OPERATION, idempotencyKey, requestHash, 201, response, actorUserId
        );

        return response;
    }

    private void ensureModifiableAndVersion(Bill bill, Long expectedVersion) {
        if (bill.getStatus() == BillStatus.CANCELLED) {
            throw new BillCancelledException("Bill is cancelled and cannot be modified");
        }
        if (!Objects.equals(bill.getVersion(), expectedVersion)) {
            throw new StaleVersionException("Bill was modified by another request");
        }
    }

    private Map<UUID, Integer> validateReturnRequests(
            List<ReturnLineRequest> returnedLines,
            Map<UUID, BillLine> linesByLogicalId) {
        Map<UUID, Integer> requestedQtyByLineId = new LinkedHashMap<>();
        for (ReturnLineRequest req : returnedLines) {
            BillLine line = linesByLogicalId.get(req.lineId());
            if (line == null) {
                throw new ResourceNotFoundException("Bill line not found: " + req.lineId());
            }
            int combined = requestedQtyByLineId.merge(req.lineId(), req.quantity(), Integer::sum);
            if (combined > line.getReturnableQuantity()) {
                throw new ReturnQuantityExceededException(
                        "Requested return quantity " + combined + " exceeds remaining returnable quantity "
                                + line.getReturnableQuantity() + " for line " + req.lineId()
                );
            }
        }
        return requestedQtyByLineId;
    }

    private NetSettlementResult validateSettlement(
            Bill bill,
            BigDecimal newTotal,
            List<ParsedSettlement> parsedSettlement) {
        BigDecimal totalPayments = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal totalRefunds = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (ParsedSettlement s : parsedSettlement) {
            if (s.direction() == PaymentDirection.PAYMENT) {
                totalPayments = totalPayments.add(s.amount());
            } else {
                totalRefunds = totalRefunds.add(s.amount());
            }
        }

        BigDecimal currentNetPaid = bill.getPaid();
        if (totalRefunds.compareTo(currentNetPaid) > 0) {
            throw new RequestValidationException("settlement", "Refund amount exceeds available net collection");
        }

        BigDecimal paidAfterRefunds = currentNetPaid.subtract(totalRefunds);
        if (paidAfterRefunds.compareTo(newTotal) > 0) {
            BigDecimal requiredRefund = paidAfterRefunds.subtract(newTotal);
            throw new RequestValidationException(
                    "settlement",
                    "Refund of at least " + CatalogValues.formatPrice(requiredRefund)
                            + " is required because adjusted bill total is below net collected amount"
            );
        }

        BigDecimal newNetPaid = paidAfterRefunds.add(totalPayments);
        if (newNetPaid.compareTo(newTotal) > 0) {
            throw new PaymentExceedsBalanceException("Settlement payment exceeds adjusted bill total");
        }

        BigDecimal newBalanceDue = newTotal.subtract(newNetPaid);
        if (newBalanceDue.compareTo(BigDecimal.ZERO) > 0 && bill.getCustomer() == null) {
            throw new RequestValidationException("settlement", "Customer is required when balanceDue is positive");
        }

        return new NetSettlementResult(newNetPaid, newBalanceDue);
    }

    private void persistSettlementEntries(
            Bill bill,
            List<ParsedSettlement> parsedSettlement,
            BillingEvent event,
            BillRevision revision,
            Instant occurredAt,
            UUID actorUserId) {
        for (ParsedSettlement entry : parsedSettlement) {
            paymentEntryRepository.save(new PaymentEntry(
                    bill,
                    entry.direction(),
                    entry.mode(),
                    entry.amount(),
                    entry.reference(),
                    event,
                    revision,
                    occurredAt,
                    actorUserId
            ));
        }
    }

    private Map<String, Object> buildCanonicalRevisionRequest(
            UUID billId,
            CreateBillRevisionRequest request,
            String normalizedReason,
            List<SettlementEntryRequest> settlementRequests) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("billId", billId.toString());
        canonical.put("expectedVersion", request.expectedVersion());
        canonical.put("reason", normalizedReason);
        canonical.put("expectedTotal", request.expectedTotal() != null ? request.expectedTotal().trim() : null);

        List<Map<String, Object>> lineList = new ArrayList<>(request.lines().size());
        for (EditBillLineRequest line : request.lines()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineId", line.lineId() != null ? line.lineId().toString() : null);
            m.put("skuId", line.skuId().toString());
            m.put("quantity", line.quantity());
            m.put("batchId", line.batchId() != null ? line.batchId().toString() : null);
            m.put("discountAmount", line.discountAmount() != null ? line.discountAmount().trim() : null);
            m.put("unitPriceOverride", line.unitPriceOverride() != null ? line.unitPriceOverride().trim() : null);
            lineList.add(m);
        }
        canonical.put("lines", lineList);
        canonical.put("settlement", stockHelper.canonicalizeSettlement(settlementRequests));
        return canonical;
    }

    private Map<String, Object> buildCanonicalReturnRequest(
            UUID billId,
            Long expectedVersion,
            String normalizedReason,
            List<ReturnLineRequest> returnedLines,
            List<SettlementEntryRequest> settlementRequests) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("billId", billId.toString());
        canonical.put("expectedVersion", expectedVersion);
        canonical.put("reason", normalizedReason);
        canonical.put("returnedLines", canonicalizeReturnedLines(returnedLines));
        canonical.put("settlement", stockHelper.canonicalizeSettlement(settlementRequests));
        return canonical;
    }

    private Map<String, Object> buildCanonicalExchangeRequest(
            UUID billId,
            ExchangeBillRequest request,
            String normalizedReason,
            List<SettlementEntryRequest> settlementRequests) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("billId", billId.toString());
        canonical.put("expectedVersion", request.expectedVersion());
        canonical.put("reason", normalizedReason);
        canonical.put("returnedLines", canonicalizeReturnedLines(request.returnedLines()));
        canonical.put("replacementLines", stockHelper.canonicalizeBillLines(request.replacementLines()));
        canonical.put("settlement", stockHelper.canonicalizeSettlement(settlementRequests));
        return canonical;
    }

    private Map<String, Object> buildCanonicalCancelRequest(
            UUID billId,
            Long expectedVersion,
            String normalizedReason,
            List<SettlementEntryRequest> settlementRequests) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("billId", billId.toString());
        canonical.put("expectedVersion", expectedVersion);
        canonical.put("reason", normalizedReason);
        canonical.put("settlement", stockHelper.canonicalizeSettlement(settlementRequests));
        return canonical;
    }

    private Map<String, Object> buildCanonicalPaymentRequest(
            UUID billId,
            Long expectedVersion,
            String mode,
            BigDecimal amount,
            String normalizedRef) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("billId", billId.toString());
        canonical.put("expectedVersion", expectedVersion);
        canonical.put("mode", mode);
        canonical.put("amount", CatalogValues.formatPrice(amount));
        canonical.put("reference", normalizedRef);
        return canonical;
    }

    private List<Map<String, Object>> canonicalizeReturnedLines(List<ReturnLineRequest> returnedLines) {
        List<Map<String, Object>> list = new ArrayList<>(returnedLines.size());
        for (ReturnLineRequest req : returnedLines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineId", req.lineId().toString());
            m.put("quantity", req.quantity());
            m.put("disposition", req.disposition().name());
            list.add(m);
        }
        return list;
    }
}
