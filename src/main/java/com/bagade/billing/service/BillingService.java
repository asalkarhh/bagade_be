package com.bagade.billing.service;

import com.bagade.billing.dto.BillAllocationResponse;
import com.bagade.billing.dto.BillDetailResponse;
import com.bagade.billing.dto.BillLineDetailResponse;
import com.bagade.billing.dto.BillPaymentRequest;
import com.bagade.billing.dto.BillPreviewLineResponse;
import com.bagade.billing.dto.BillPreviewResponse;
import com.bagade.billing.dto.BillPrintCorrectionResponse;
import com.bagade.billing.dto.BillPrintLineResponse;
import com.bagade.billing.dto.BillPrintPaymentResponse;
import com.bagade.billing.dto.BillPrintResponse;
import com.bagade.billing.dto.BillRevisionRefResponse;
import com.bagade.billing.dto.BillSummaryResponse;
import com.bagade.billing.dto.BillingEventRefResponse;
import com.bagade.billing.dto.CreateBillRequest;
import com.bagade.billing.dto.CreateBillResponse;
import com.bagade.billing.dto.PaymentEntryResponse;
import com.bagade.billing.dto.PreviewBillRequest;
import com.bagade.billing.dto.SaleAllocationReversalResponse;
import com.bagade.billing.dto.SalesAdjustmentResponse;
import com.bagade.billing.entity.Bill;
import com.bagade.billing.entity.BillLine;
import com.bagade.billing.entity.BillRevision;
import com.bagade.billing.entity.BillingEvent;
import com.bagade.billing.entity.BillingEventLine;
import com.bagade.billing.entity.BillingEventType;
import com.bagade.billing.entity.Customer;
import com.bagade.billing.entity.PaymentDirection;
import com.bagade.billing.entity.PaymentEntry;
import com.bagade.billing.entity.PaymentMode;
import com.bagade.billing.entity.SaleAllocation;
import com.bagade.billing.entity.SalesAdjustment;
import com.bagade.billing.repository.BillListRow;
import com.bagade.billing.repository.BillRepository;
import com.bagade.billing.repository.BillRevisionRepository;
import com.bagade.billing.repository.BillingEventRepository;
import com.bagade.billing.repository.CustomerRepository;
import com.bagade.billing.repository.PaymentEntryRepository;
import com.bagade.billing.repository.SaleAllocationRepository;
import com.bagade.billing.repository.SaleAllocationReversalRepository;
import com.bagade.billing.repository.SalesAdjustmentRepository;
import com.bagade.billing.service.BillingStockHelper.ComputedLine;
import com.bagade.billing.service.BillingStockHelper.PlannedAllocation;
import com.bagade.catalog.entity.Sku;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.dto.PagedResponse;
import com.bagade.common.error.BillTotalsSnapshot;
import com.bagade.common.error.FieldErrorItem;
import com.bagade.common.error.PriceChangedException;
import com.bagade.common.error.RequestValidationException;
import com.bagade.common.error.ResourceNotFoundException;
import com.bagade.common.idempotency.IdempotencyService;
import com.bagade.common.util.PaginationHelper;
import com.bagade.inventory.dto.EligibleBatchResponse;
import com.bagade.inventory.entity.BatchBalance;
import com.bagade.inventory.entity.SkuStockState;
import com.bagade.inventory.repository.BatchBalanceRepository;
import com.bagade.inventory.service.InventoryMovementService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class BillingService {

    private static final String ISSUE_BILL_OPERATION = "POST_BILL";
    private static final String RECEIPT_PROFILE_80MM = "80mm";

    private final BillRepository billRepository;
    private final BillRevisionRepository billRevisionRepository;
    private final BillingEventRepository billingEventRepository;
    private final SaleAllocationRepository saleAllocationRepository;
    private final SaleAllocationReversalRepository saleAllocationReversalRepository;
    private final SalesAdjustmentRepository salesAdjustmentRepository;
    private final PaymentEntryRepository paymentEntryRepository;
    private final CustomerRepository customerRepository;
    private final BatchBalanceRepository batchBalanceRepository;
    private final InventoryMovementService inventoryMovementService;
    private final BillingStockHelper stockHelper;
    private final IdempotencyService idempotencyService;
    private final BillReceiptPdfRenderer pdfRenderer;
    private final String shopName;
    private final ZoneId shopZone;

    @PersistenceContext
    private EntityManager entityManager;

    public BillingService(
            BillRepository billRepository,
            BillRevisionRepository billRevisionRepository,
            BillingEventRepository billingEventRepository,
            SaleAllocationRepository saleAllocationRepository,
            SaleAllocationReversalRepository saleAllocationReversalRepository,
            SalesAdjustmentRepository salesAdjustmentRepository,
            PaymentEntryRepository paymentEntryRepository,
            CustomerRepository customerRepository,
            BatchBalanceRepository batchBalanceRepository,
            InventoryMovementService inventoryMovementService,
            BillingStockHelper stockHelper,
            IdempotencyService idempotencyService,
            BillReceiptPdfRenderer pdfRenderer,
            @Value("${app.shop.name:Bagade Clothing}") String shopName,
            @Value("${app.shop.timezone:Asia/Kolkata}") String shopTimezone) {
        this.billRepository = billRepository;
        this.billRevisionRepository = billRevisionRepository;
        this.billingEventRepository = billingEventRepository;
        this.saleAllocationRepository = saleAllocationRepository;
        this.saleAllocationReversalRepository = saleAllocationReversalRepository;
        this.salesAdjustmentRepository = salesAdjustmentRepository;
        this.paymentEntryRepository = paymentEntryRepository;
        this.customerRepository = customerRepository;
        this.batchBalanceRepository = batchBalanceRepository;
        this.inventoryMovementService = inventoryMovementService;
        this.stockHelper = stockHelper;
        this.idempotencyService = idempotencyService;
        this.pdfRenderer = pdfRenderer;
        this.shopName = shopName;
        this.shopZone = ZoneId.of(shopTimezone);
    }

    @Transactional(readOnly = true)
    public BillPreviewResponse previewBill(PreviewBillRequest request) {
        Map<UUID, Sku> skusById = stockHelper.loadSkusWithActiveCheck(request.lines(), "lines");
        List<ComputedLine> computedLines = stockHelper.computeLines(request.lines(), skusById, "lines");

        Set<UUID> skuIds = skusById.keySet();
        List<BatchBalance> positiveBalances = batchBalanceRepository.findSellablePositiveBalancesForSkus(skuIds);
        stockHelper.validateExplicitBatches(computedLines, positiveBalances, "lines");

        Map<UUID, String> internalBarcodesByBatchId = stockHelper.loadInternalBarcodes(positiveBalances);
        Map<UUID, List<BatchBalance>> balancesBySku = stockHelper.groupBalancesBySku(positiveBalances);
        Map<UUID, BatchBalance> balanceByBatchId = stockHelper.indexBalancesByBatchId(positiveBalances);
        Map<UUID, Integer> remainingByBatchId = stockHelper.initRemainingMap(positiveBalances);

        // Allocate explicit-batch lines first so FIFO lines do not consume explicitly tagged batches
        Map<Integer, Boolean> sufficientByIndex = new HashMap<>();
        Map<Integer, Long> availableBeforeByIndex = new HashMap<>();
        Map<Integer, Boolean> batchSelectionByIndex = new HashMap<>();
        Map<Integer, List<EligibleBatchResponse>> selectableByIndex = new HashMap<>();

        for (ComputedLine cl : computedLines) {
            if (cl.request().batchId() != null) {
                BatchBalance bal = balanceByBatchId.get(cl.request().batchId());
                int available = bal != null ? remainingByBatchId.getOrDefault(bal.getBatch().getId(), 0) : 0;
                availableBeforeByIndex.put(cl.index(), (long) available);
                batchSelectionByIndex.put(cl.index(), false);
                selectableByIndex.put(cl.index(), List.of());

                if (available >= cl.request().quantity()) {
                    sufficientByIndex.put(cl.index(), true);
                    if (bal != null) {
                        remainingByBatchId.put(bal.getBatch().getId(), available - cl.request().quantity());
                    }
                } else {
                    sufficientByIndex.put(cl.index(), false);
                    if (bal != null && available > 0) {
                        remainingByBatchId.put(bal.getBatch().getId(), 0);
                    }
                }
            }
        }

        for (ComputedLine cl : computedLines) {
            if (cl.request().batchId() == null) {
                List<BatchBalance> skuBalances = balancesBySku.getOrDefault(cl.sku().getId(), List.of());
                long totalAvailable = 0;
                for (BatchBalance b : skuBalances) {
                    totalAvailable += remainingByBatchId.getOrDefault(b.getBatch().getId(), 0);
                }
                availableBeforeByIndex.put(cl.index(), totalAvailable);

                List<EligibleBatchResponse> differingMrpBatches = stockHelper.findDifferingMrpBatches(
                        skuBalances, remainingByBatchId, internalBarcodesByBatchId
                );
                boolean needsSelection = !differingMrpBatches.isEmpty();
                batchSelectionByIndex.put(cl.index(), needsSelection);
                selectableByIndex.put(cl.index(), differingMrpBatches);

                if (totalAvailable >= cl.request().quantity()) {
                    sufficientByIndex.put(cl.index(), true);
                    int needed = cl.request().quantity();
                    for (BatchBalance b : skuBalances) {
                        if (needed == 0) {
                            break;
                        }
                        UUID batchId = b.getBatch().getId();
                        int rem = remainingByBatchId.getOrDefault(batchId, 0);
                        if (rem > 0) {
                            int take = Math.min(rem, needed);
                            remainingByBatchId.put(batchId, rem - take);
                            needed -= take;
                        }
                    }
                } else {
                    sufficientByIndex.put(cl.index(), false);
                }
            }
        }

        BigDecimal subtotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal discountTotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        boolean canCheckout = true;
        List<BillPreviewLineResponse> previewLines = new ArrayList<>(computedLines.size());

        for (ComputedLine cl : computedLines) {
            subtotal = subtotal.add(cl.lineSubtotal());
            discountTotal = discountTotal.add(cl.discount());

            boolean sufficient = sufficientByIndex.get(cl.index());
            boolean needsBatch = batchSelectionByIndex.get(cl.index());
            List<String> issues = new ArrayList<>();
            if (needsBatch) {
                issues.add("BATCH_SELECTION_REQUIRED");
            }
            if (!sufficient) {
                issues.add("INSUFFICIENT_STOCK");
            }
            if (!issues.isEmpty()) {
                canCheckout = false;
            }

            previewLines.add(new BillPreviewLineResponse(
                    cl.sku().getId(),
                    cl.sku().getSkuCode(),
                    cl.sku().getProduct().getName(),
                    cl.sku().getSize(),
                    cl.request().batchId(),
                    cl.request().quantity(),
                    CatalogValues.formatPrice(cl.unitPrice()),
                    CatalogValues.formatPrice(cl.discount()),
                    CatalogValues.formatPrice(cl.lineSubtotal()),
                    CatalogValues.formatPrice(cl.lineTotal()),
                    availableBeforeByIndex.get(cl.index()),
                    sufficient,
                    needsBatch,
                    selectableByIndex.get(cl.index()),
                    issues
            ));
        }

        BigDecimal total = subtotal.subtract(discountTotal);
        return new BillPreviewResponse(
                previewLines,
                CatalogValues.formatPrice(subtotal),
                CatalogValues.formatPrice(discountTotal),
                CatalogValues.formatPrice(total),
                canCheckout
        );
    }

    private record ParsedPayment(PaymentMode mode, BigDecimal amount, String reference) {}

    @Transactional
    public CreateBillResponse createBill(
            CreateBillRequest request,
            String rawIdempotencyKey,
            UUID actorUserId) {

        String idempotencyKey = idempotencyService.validateKey(rawIdempotencyKey);
        List<BillPaymentRequest> paymentRequests = request.payments() != null ? request.payments() : List.of();
        String requestHash = idempotencyService.computeRequestHash(
                buildCanonicalBillRequest(request, paymentRequests)
        );

        var fastReplay = idempotencyService.findReplay(
                ISSUE_BILL_OPERATION, idempotencyKey, requestHash, CreateBillResponse.class);
        if (fastReplay.isPresent()) {
            return fastReplay.get().body();
        }

        Map<UUID, Sku> skusById = stockHelper.loadSkusWithActiveCheck(request.lines(), "lines");
        List<ComputedLine> computedLines = stockHelper.computeLines(request.lines(), skusById, "lines");
        List<ParsedPayment> parsedPayments = validateAndParsePayments(paymentRequests);

        Customer customer = null;
        if (request.customerId() != null) {
            customer = customerRepository.findById(request.customerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        }

        // Locking order (same as BE04): 1) SkuStockState sorted by skuId ASC, 2) BatchBalance (FIFO order)
        Set<UUID> sortedSkuIds = new TreeSet<>(skusById.keySet());
        List<SkuStockState> lockedStockStates = inventoryMovementService.lockOrInitializeStockStates(sortedSkuIds);

        var postLockReplay = idempotencyService.findReplay(
                ISSUE_BILL_OPERATION, idempotencyKey, requestHash, CreateBillResponse.class);
        if (postLockReplay.isPresent()) {
            return postLockReplay.get().body();
        }

        List<BatchBalance> lockedBalances = batchBalanceRepository.findSellableBalancesForSkusForUpdate(sortedSkuIds);
        stockHelper.validateExplicitBatches(computedLines, lockedBalances, "lines");

        Map<UUID, String> internalBarcodesByBatchId = stockHelper.loadInternalBarcodes(lockedBalances);
        Map<UUID, List<BatchBalance>> balancesBySku = stockHelper.groupBalancesBySku(lockedBalances);
        Map<UUID, BatchBalance> balanceByBatchId = stockHelper.indexBalancesByBatchId(lockedBalances);
        Map<UUID, Integer> remainingByBatchId = stockHelper.initRemainingMap(lockedBalances);

        stockHelper.checkDifferingMrpForImplicitLines(
                computedLines, balancesBySku, remainingByBatchId, internalBarcodesByBatchId
        );

        BigDecimal subtotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal discountTotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (ComputedLine cl : computedLines) {
            subtotal = subtotal.add(cl.lineSubtotal());
            discountTotal = discountTotal.add(cl.discount());
        }
        BigDecimal total = subtotal.subtract(discountTotal);
        BigDecimal expectedTotal = CatalogValues.parsePrice(request.expectedTotal(), "expectedTotal");

        if (total.compareTo(expectedTotal) != 0) {
            throw new PriceChangedException(
                    "Bill total changed since preview; please review updated totals",
                    new BillTotalsSnapshot(
                            CatalogValues.formatPrice(subtotal),
                            CatalogValues.formatPrice(discountTotal),
                            CatalogValues.formatPrice(total)
                    )
            );
        }

        BigDecimal paid = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (ParsedPayment p : parsedPayments) {
            paid = paid.add(p.amount());
        }
        if (paid.compareTo(total) > 0) {
            throw new RequestValidationException("payments", "Total payments cannot exceed bill total");
        }
        BigDecimal balanceDue = total.subtract(paid);
        if (balanceDue.compareTo(BigDecimal.ZERO) > 0 && customer == null) {
            throw new RequestValidationException("customerId", "Customer is required when balanceDue is positive");
        }

        List<PlannedAllocation> plannedAllocations = stockHelper.allocateStock(
                computedLines, balancesBySku, balanceByBatchId, remainingByBatchId
        );

        String billNumber = String.format("BILL-%06d", billRepository.nextBillNumberSequence());
        Instant issuedAt = Instant.now();
        LocalDate issueDate = LocalDate.ofInstant(issuedAt, shopZone);

        Bill bill = billRepository.saveAndFlush(new Bill(
                billNumber,
                customer,
                issueDate,
                issuedAt,
                actorUserId,
                subtotal,
                discountTotal,
                total,
                paid,
                balanceDue
        ));

        BillRevision revision = new BillRevision(
                bill,
                1,
                subtotal,
                discountTotal,
                total,
                paid,
                balanceDue,
                issuedAt,
                actorUserId
        );

        List<UUID> logicalLineIds = new ArrayList<>(computedLines.size());
        for (ComputedLine cl : computedLines) {
            UUID logicalLineId = UUID.randomUUID();
            logicalLineIds.add(logicalLineId);
            Sku sku = cl.sku();
            revision.addLine(new BillLine(
                    logicalLineId,
                    cl.index(),
                    sku,
                    sku.getSkuCode(),
                    sku.getProduct().getName(),
                    sku.getSize(),
                    cl.request().quantity(),
                    cl.unitPrice(),
                    cl.discount(),
                    cl.lineTotal()
            ));
        }
        BillRevision savedRevision = billRevisionRepository.saveAndFlush(revision);

        BillingEvent issueEvent = new BillingEvent(bill, savedRevision, BillingEventType.ISSUE, issuedAt, actorUserId);
        for (int i = 0; i < computedLines.size(); i++) {
            ComputedLine cl = computedLines.get(i);
            issueEvent.addLine(new BillingEventLine(
                    logicalLineIds.get(i),
                    cl.sku(),
                    cl.request().quantity(),
                    cl.lineTotal()
            ));
        }
        BillingEvent savedIssueEvent = billingEventRepository.saveAndFlush(issueEvent);

        Map<Integer, BillLine> savedLineByIndex = new HashMap<>();
        List<BillLine> savedBillLines = savedRevision.getLines();
        for (int i = 0; i < savedBillLines.size(); i++) {
            savedLineByIndex.put(i, savedBillLines.get(i));
        }

        List<BillAllocationResponse> allocationResponses = stockHelper.persistPlannedAllocations(
                plannedAllocations, savedLineByIndex, issuedAt, actorUserId, null
        );

        for (ParsedPayment payment : parsedPayments) {
            paymentEntryRepository.save(new PaymentEntry(
                    bill,
                    PaymentDirection.PAYMENT,
                    payment.mode(),
                    payment.amount(),
                    payment.reference(),
                    savedIssueEvent,
                    savedRevision,
                    issuedAt,
                    actorUserId
            ));
        }

        inventoryMovementService.incrementStockVersions(lockedStockStates);
        entityManager.flush();

        CreateBillResponse response = new CreateBillResponse(
                bill.getId(),
                bill.getBillNumber(),
                bill.getVersion(),
                bill.getIssueDate(),
                bill.getIssuedAt(),
                bill.getStatus(),
                CatalogValues.formatPrice(bill.getSubtotal()),
                CatalogValues.formatPrice(bill.getDiscountTotal()),
                CatalogValues.formatPrice(bill.getTotal()),
                CatalogValues.formatPrice(bill.getPaid()),
                CatalogValues.formatPrice(bill.getBalanceDue()),
                allocationResponses
        );

        idempotencyService.saveResult(
                ISSUE_BILL_OPERATION,
                idempotencyKey,
                requestHash,
                201,
                response,
                actorUserId
        );

        return response;
    }

    @Transactional(readOnly = true)
    public PagedResponse<BillSummaryResponse> listBills(
            String billNumber,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            int page,
            int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new RequestValidationException(
                    "Validation failed",
                    List.of(
                            new FieldErrorItem("from", "'from' date must not be after 'to' date"),
                            new FieldErrorItem("to", "'to' date must not be before 'from' date")
                    )
            );
        }

        Pageable pageable = PaginationHelper.createPageable(page, size, Sort.unsorted());
        Page<BillListRow> rows = billRepository.searchBills(
                CatalogValues.buildLikePattern(billNumber),
                from,
                to,
                customerId,
                pageable
        );

        Page<BillSummaryResponse> mapped = rows.map(row -> new BillSummaryResponse(
                row.billId(),
                row.billNumber(),
                row.issueDate(),
                row.status(),
                CatalogValues.formatPrice(row.total()),
                CatalogValues.formatPrice(row.paid()),
                CatalogValues.formatPrice(row.balanceDue())
        ));

        return PagedResponse.from(mapped);
    }

    @Transactional(readOnly = true)
    public BillDetailResponse getBillDetail(UUID billId) {
        Bill bill = billRepository.findByIdWithCustomer(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        BillRevision currentRevision = billRevisionRepository
                .findByBillIdAndRevisionNumberWithLines(billId, bill.getCurrentRevisionNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Bill revision not found"));

        Map<UUID, List<BillAllocationResponse>> allocationsByLogicalLineId = new LinkedHashMap<>();
        for (SaleAllocation alloc : saleAllocationRepository.findByBillIdOrdered(billId)) {
            int remaining = alloc.getRemainingQuantity();
            if (remaining > 0) {
                allocationsByLogicalLineId.computeIfAbsent(alloc.getLogicalLineId(), ignored -> new ArrayList<>())
                        .add(new BillAllocationResponse(
                                alloc.getSku().getId(),
                                alloc.getBatch().getId(),
                                remaining,
                                CatalogValues.formatPrice(alloc.getCapturedUnitCost()),
                                alloc.getAttributionMethod()
                        ));
            }
        }

        List<BillLineDetailResponse> lineDetails = currentRevision.getLines().stream()
                .map(line -> new BillLineDetailResponse(
                        line.getId(),
                        line.getLogicalLineId(),
                        line.getSku().getId(),
                        line.getSkuCodeSnapshot(),
                        line.getProductNameSnapshot(),
                        line.getSizeSnapshot(),
                        line.getQuantity(),
                        line.getReturnedQuantity(),
                        line.getReturnableQuantity(),
                        CatalogValues.formatPrice(line.getUnitSellingPrice()),
                        CatalogValues.formatPrice(line.getDiscountAmount()),
                        CatalogValues.formatPrice(line.getLineTotal()),
                        allocationsByLogicalLineId.getOrDefault(line.getLogicalLineId(), List.of())
                ))
                .toList();

        List<PaymentEntryResponse> payments = paymentEntryRepository.findByBillIdOrderByPostedAtAscIdAsc(billId)
                .stream()
                .map(p -> new PaymentEntryResponse(
                        p.getId(),
                        p.getDirection(),
                        p.getMode(),
                        CatalogValues.formatPrice(p.getAmount()),
                        p.getReference(),
                        p.getPostedAt(),
                        p.getPostedBy()
                ))
                .toList();

        List<BillRevisionRefResponse> revisions = billRevisionRepository.findByBillIdOrderByRevisionNumberAsc(billId)
                .stream()
                .map(rev -> new BillRevisionRefResponse(
                        rev.getId(),
                        rev.getRevisionNumber(),
                        CatalogValues.formatPrice(rev.getSubtotal()),
                        CatalogValues.formatPrice(rev.getDiscountTotal()),
                        CatalogValues.formatPrice(rev.getTotal()),
                        rev.getCreatedAt()
                ))
                .toList();

        List<BillingEventRefResponse> events = billingEventRepository.findByBillIdWithRevisionOrderByOccurredAtAsc(billId)
                .stream()
                .map(ev -> new BillingEventRefResponse(
                        ev.getId(),
                        ev.getEventType(),
                        ev.getBillRevision().getRevisionNumber(),
                        ev.getReason(),
                        ev.getOccurredAt(),
                        ev.getActorId()
                ))
                .toList();

        List<SalesAdjustmentResponse> salesAdjustments = salesAdjustmentRepository
                .findByBillIdOrderByOccurredAtAscIdAsc(billId)
                .stream()
                .map(adj -> new SalesAdjustmentResponse(
                        adj.getId(),
                        adj.getAdjustmentType(),
                        CatalogValues.formatPrice(adj.getSubtotalDelta()),
                        CatalogValues.formatPrice(adj.getDiscountDelta()),
                        CatalogValues.formatPrice(adj.getAmountDelta()),
                        adj.getOccurredAt(),
                        adj.getPostedBy()
                ))
                .toList();

        List<SaleAllocationReversalResponse> allocationReversals = saleAllocationReversalRepository
                .findByBillIdOrderByReversedAtAsc(billId)
                .stream()
                .map(rev -> new SaleAllocationReversalResponse(
                        rev.getId(),
                        rev.getSaleAllocation().getId(),
                        rev.getBillingEvent().getId(),
                        rev.getSku().getId(),
                        rev.getBatch().getId(),
                        rev.getQuantity(),
                        CatalogValues.formatPrice(rev.getCapturedUnitCost()),
                        rev.getDisposition(),
                        rev.getReversedAt()
                ))
                .toList();

        Customer customer = bill.getCustomer();
        return new BillDetailResponse(
                bill.getId(),
                bill.getBillNumber(),
                bill.getIssueDate(),
                bill.getIssuedAt(),
                bill.getStatus(),
                bill.getCurrentRevisionNumber(),
                bill.getVersion(),
                customer != null ? customer.getId() : null,
                customer != null ? customer.getName() : null,
                customer != null ? customer.getContact() : null,
                CatalogValues.formatPrice(bill.getSubtotal()),
                CatalogValues.formatPrice(bill.getDiscountTotal()),
                CatalogValues.formatPrice(bill.getTotal()),
                CatalogValues.formatPrice(bill.getPaid()),
                CatalogValues.formatPrice(bill.getBalanceDue()),
                lineDetails,
                payments,
                revisions,
                events,
                salesAdjustments,
                allocationReversals
        );
    }

    @Transactional(readOnly = true)
    public BillPrintResponse getPrintData(UUID billId, Integer revisionNumber) {
        Bill bill = billRepository.findByIdWithCustomer(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        Customer customer = bill.getCustomer();

        if (revisionNumber != null) {
            BillRevision revision = billRevisionRepository
                    .findByBillIdAndRevisionNumberWithLines(billId, revisionNumber)
                    .orElseThrow(() -> new ResourceNotFoundException("Bill revision not found: " + revisionNumber));

            List<BillPrintLineResponse> lines = revision.getLines().stream()
                    .map(line -> new BillPrintLineResponse(
                            line.getSkuCodeSnapshot(),
                            line.getProductNameSnapshot(),
                            line.getSizeSnapshot(),
                            line.getQuantity(),
                            line.getInitialReturnedQuantity(),
                            CatalogValues.formatPrice(line.getUnitSellingPrice()),
                            CatalogValues.formatPrice(line.getDiscountAmount()),
                            CatalogValues.formatPrice(line.getLineTotal())
                    ))
                    .toList();

            List<BillPrintPaymentResponse> payments = paymentEntryRepository
                    .findByBillIdUpToRevisionOrderByPostedAtAscIdAsc(billId, revisionNumber)
                    .stream()
                    .map(p -> new BillPrintPaymentResponse(
                            p.getDirection(),
                            p.getMode(),
                            CatalogValues.formatPrice(p.getAmount()),
                            p.getReference(),
                            p.getPostedAt()
                    ))
                    .toList();

            return new BillPrintResponse(
                    shopName,
                    RECEIPT_PROFILE_80MM,
                    bill.getId(),
                    bill.getBillNumber(),
                    null,
                    revision.getRevisionNumber(),
                    bill.getIssueDate(),
                    bill.getIssuedAt(),
                    customer != null ? customer.getName() : null,
                    customer != null ? customer.getContact() : null,
                    lines,
                    CatalogValues.formatPrice(revision.getSubtotal()),
                    CatalogValues.formatPrice(revision.getDiscountTotal()),
                    CatalogValues.formatPrice(revision.getTotal()),
                    CatalogValues.formatPrice(revision.getPaidSnapshot()),
                    CatalogValues.formatPrice(revision.getBalanceDueSnapshot()),
                    payments,
                    List.of()
            );
        }

        BillRevision currentRevision = billRevisionRepository
                .findByBillIdAndRevisionNumberWithLines(billId, bill.getCurrentRevisionNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Bill revision not found"));

        List<BillPrintLineResponse> lines = currentRevision.getLines().stream()
                .map(line -> new BillPrintLineResponse(
                        line.getSkuCodeSnapshot(),
                        line.getProductNameSnapshot(),
                        line.getSizeSnapshot(),
                        line.getQuantity(),
                        line.getReturnedQuantity(),
                        CatalogValues.formatPrice(line.getUnitSellingPrice()),
                        CatalogValues.formatPrice(line.getDiscountAmount()),
                        CatalogValues.formatPrice(line.getLineTotal())
                ))
                .toList();

        List<BillPrintPaymentResponse> payments = paymentEntryRepository.findByBillIdOrderByPostedAtAscIdAsc(billId)
                .stream()
                .map(p -> new BillPrintPaymentResponse(
                        p.getDirection(),
                        p.getMode(),
                        CatalogValues.formatPrice(p.getAmount()),
                        p.getReference(),
                        p.getPostedAt()
                ))
                .toList();

        Map<UUID, SalesAdjustment> adjustmentByEventId = new HashMap<>();
        for (SalesAdjustment adj : salesAdjustmentRepository.findByBillIdOrderByOccurredAtAscIdAsc(billId)) {
            adjustmentByEventId.put(adj.getBillingEvent().getId(), adj);
        }

        List<BillPrintCorrectionResponse> corrections = billingEventRepository
                .findByBillIdWithRevisionOrderByOccurredAtAsc(billId)
                .stream()
                .filter(ev -> ev.getEventType() != BillingEventType.ISSUE)
                .map(ev -> {
                    SalesAdjustment adj = adjustmentByEventId.get(ev.getId());
                    String delta = adj != null ? CatalogValues.formatPrice(adj.getAmountDelta()) : "0.00";
                    return new BillPrintCorrectionResponse(ev.getEventType(), ev.getOccurredAt(), ev.getReason(), delta);
                })
                .toList();

        return new BillPrintResponse(
                shopName,
                RECEIPT_PROFILE_80MM,
                bill.getId(),
                bill.getBillNumber(),
                bill.getStatus(),
                bill.getCurrentRevisionNumber(),
                bill.getIssueDate(),
                bill.getIssuedAt(),
                customer != null ? customer.getName() : null,
                customer != null ? customer.getContact() : null,
                lines,
                CatalogValues.formatPrice(bill.getSubtotal()),
                CatalogValues.formatPrice(bill.getDiscountTotal()),
                CatalogValues.formatPrice(bill.getTotal()),
                CatalogValues.formatPrice(bill.getPaid()),
                CatalogValues.formatPrice(bill.getBalanceDue()),
                payments,
                corrections
        );
    }

    @Transactional(readOnly = true)
    public byte[] renderPrintPdf(UUID billId, Integer revisionNumber) {
        BillPrintResponse printData = getPrintData(billId, revisionNumber);
        return pdfRenderer.render80mmPdf(printData, shopZone);
    }

    private List<ParsedPayment> validateAndParsePayments(List<BillPaymentRequest> paymentRequests) {
        List<ParsedPayment> parsed = new ArrayList<>(paymentRequests.size());
        List<FieldErrorItem> errors = new ArrayList<>();
        for (int i = 0; i < paymentRequests.size(); i++) {
            BillPaymentRequest req = paymentRequests.get(i);
            BigDecimal amount = CatalogValues.parsePrice(req.amount(), "payments[" + i + "].amount");
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(new FieldErrorItem(
                        "payments[" + i + "].amount",
                        "Payment amount must be greater than 0.00"
                ));
            } else {
                parsed.add(new ParsedPayment(
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

    private Map<String, Object> buildCanonicalBillRequest(
            CreateBillRequest request,
            List<BillPaymentRequest> paymentRequests) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("expectedTotal", request.expectedTotal() != null ? request.expectedTotal().trim() : null);
        canonical.put("customerId", request.customerId() != null ? request.customerId().toString() : null);
        canonical.put("lines", stockHelper.canonicalizeBillLines(request.lines()));

        List<Map<String, Object>> payList = new ArrayList<>(paymentRequests.size());
        for (BillPaymentRequest p : paymentRequests) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", p.mode().name());
            m.put("amount", p.amount() != null ? p.amount().trim() : null);
            m.put("reference", CatalogValues.normalizeOptionalText(p.reference()));
            payList.add(m);
        }
        canonical.put("payments", payList);
        return canonical;
    }
}
