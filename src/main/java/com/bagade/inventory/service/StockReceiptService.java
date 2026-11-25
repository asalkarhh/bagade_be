package com.bagade.inventory.service;

import com.bagade.catalog.entity.Sku;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.error.FieldErrorItem;
import com.bagade.common.error.PoLineMismatchException;
import com.bagade.common.error.PoQuantityExceededException;
import com.bagade.common.error.RequestValidationException;
import com.bagade.common.error.ResourceNotFoundException;
import com.bagade.common.idempotency.IdempotencyService;
import com.bagade.inventory.dto.CreateStockReceiptLineRequest;
import com.bagade.inventory.dto.CreateStockReceiptRequest;
import com.bagade.inventory.dto.StockReceiptLineResponse;
import com.bagade.inventory.dto.StockReceiptResponse;
import com.bagade.inventory.entity.BarcodeMapping;
import com.bagade.inventory.entity.BarcodeSourceType;
import com.bagade.inventory.entity.SkuStockState;
import com.bagade.inventory.entity.StockBatch;
import com.bagade.inventory.entity.StockReceipt;
import com.bagade.inventory.entity.StockReceiptLine;
import com.bagade.inventory.repository.BarcodeMappingRepository;
import com.bagade.inventory.repository.StockBatchRepository;
import com.bagade.inventory.repository.StockReceiptRepository;
import com.bagade.purchasing.entity.PurchaseOrder;
import com.bagade.purchasing.entity.PurchaseOrderLine;
import com.bagade.purchasing.entity.PurchaseOrderStatus;
import com.bagade.purchasing.entity.Vendor;
import com.bagade.purchasing.repository.PurchaseOrderLineRepository;
import com.bagade.purchasing.repository.PurchaseOrderRepository;
import com.bagade.purchasing.repository.VendorRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
public class StockReceiptService {

    private static final String RECEIPT_OPERATION = "POST_STOCK_RECEIPT";

    private final StockReceiptRepository stockReceiptRepository;
    private final StockBatchRepository stockBatchRepository;
    private final BarcodeMappingRepository barcodeMappingRepository;
    private final VendorRepository vendorRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final SkuRepository skuRepository;
    private final IdempotencyService idempotencyService;
    private final InventoryMovementService inventoryMovementService;

    @PersistenceContext
    private EntityManager entityManager;

    public StockReceiptService(
            StockReceiptRepository stockReceiptRepository,
            StockBatchRepository stockBatchRepository,
            BarcodeMappingRepository barcodeMappingRepository,
            VendorRepository vendorRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderLineRepository purchaseOrderLineRepository,
            SkuRepository skuRepository,
            IdempotencyService idempotencyService,
            InventoryMovementService inventoryMovementService) {
        this.stockReceiptRepository = stockReceiptRepository;
        this.stockBatchRepository = stockBatchRepository;
        this.barcodeMappingRepository = barcodeMappingRepository;
        this.vendorRepository = vendorRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.skuRepository = skuRepository;
        this.idempotencyService = idempotencyService;
        this.inventoryMovementService = inventoryMovementService;
    }

    @Transactional
    public StockReceiptResponse postReceipt(
            CreateStockReceiptRequest request,
            String rawIdempotencyKey,
            UUID actorUserId) {

        String idempotencyKey = idempotencyService.validateKey(rawIdempotencyKey);
        String normalizedInvoiceRef = CatalogValues.normalizeOptionalText(request.vendorInvoiceRef());
        String requestHash = idempotencyService.computeRequestHash(
                buildCanonicalRequest(request, normalizedInvoiceRef)
        );

        var fastReplay = idempotencyService.findReplay(
                RECEIPT_OPERATION, idempotencyKey, requestHash, StockReceiptResponse.class);
        if (fastReplay.isPresent()) {
            return fastReplay.get().body();
        }

        PurchaseOrder po = null;
        List<PurchaseOrderLine> poLines = List.of();
        Map<UUID, PurchaseOrderLine> poLinesById = Map.of();

        // Locking order: 1) PO header, 2) PO lines (same order as BE03), 3) SkuStockState sorted by SKU ID
        if (request.poId() != null) {
            po = purchaseOrderRepository.findByIdForUpdate(request.poId())
                    .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found"));

            var postPoLockReplay = idempotencyService.findReplay(
                    RECEIPT_OPERATION, idempotencyKey, requestHash, StockReceiptResponse.class);
            if (postPoLockReplay.isPresent()) {
                return postPoLockReplay.get().body();
            }

            poLines = purchaseOrderLineRepository.findByPurchaseOrderIdForUpdate(po.getId());
            poLinesById = poLines.stream()
                    .collect(Collectors.toMap(PurchaseOrderLine::getId, Function.identity()));
        }

        Vendor vendor = vendorRepository.findById(request.vendorId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found"));
        if (!vendor.isActive()) {
            throw new RequestValidationException("vendorId", "Vendor is inactive");
        }

        if (po != null) {
            if (!Objects.equals(po.getVendor().getId(), vendor.getId())) {
                throw new PoLineMismatchException("Receipt vendor does not match purchase order vendor");
            }
            validateLinkedReceiptLines(request.lines(), poLinesById);
        } else {
            validateDirectReceiptLines(request.lines());
        }

        Set<UUID> sortedSkuIds = new TreeSet<>();
        for (CreateStockReceiptLineRequest lineReq : request.lines()) {
            sortedSkuIds.add(lineReq.skuId());
        }

        Map<UUID, Sku> skusById = skuRepository.findAllByIdWithProduct(sortedSkuIds).stream()
                .collect(Collectors.toMap(Sku::getId, Function.identity()));
        List<FieldErrorItem> skuErrors = new ArrayList<>();
        for (int i = 0; i < request.lines().size(); i++) {
            CreateStockReceiptLineRequest lineReq = request.lines().get(i);
            Sku sku = skusById.get(lineReq.skuId());
            if (sku == null) {
                throw new ResourceNotFoundException("SKU not found: " + lineReq.skuId());
            }
            if (!sku.isActive()) {
                skuErrors.add(new FieldErrorItem(
                        "lines[" + i + "].skuId",
                        "SKU is inactive: " + sku.getSkuCode()
                ));
            }
        }
        if (!skuErrors.isEmpty()) {
            throw new RequestValidationException("Validation failed", skuErrors);
        }

        List<SkuStockState> lockedStockStates = inventoryMovementService.lockOrInitializeStockStates(sortedSkuIds);

        if (po == null) {
            var postSkuLockReplay = idempotencyService.findReplay(
                    RECEIPT_OPERATION, idempotencyKey, requestHash, StockReceiptResponse.class);
            if (postSkuLockReplay.isPresent()) {
                return postSkuLockReplay.get().body();
            }
        }

        String receiptNumber = String.format("RCP-%06d", stockReceiptRepository.nextReceiptNumberSequence());
        Instant postedAt = Instant.now();

        StockReceipt receipt = new StockReceipt(
                receiptNumber,
                vendor,
                po,
                normalizedInvoiceRef,
                request.receivedDate(),
                postedAt,
                actorUserId
        );

        List<BigDecimal> parsedUnitCosts = new ArrayList<>(request.lines().size());
        List<BigDecimal> parsedMrps = new ArrayList<>(request.lines().size());

        for (int i = 0; i < request.lines().size(); i++) {
            CreateStockReceiptLineRequest lineReq = request.lines().get(i);
            Sku sku = skusById.get(lineReq.skuId());
            PurchaseOrderLine poLine = lineReq.poLineId() != null ? poLinesById.get(lineReq.poLineId()) : null;

            BigDecimal unitCost = CatalogValues.parsePrice(lineReq.unitCost(), "lines[" + i + "].unitCost");
            BigDecimal printedMrp = lineReq.printedMrp() != null
                    ? CatalogValues.parsePrice(lineReq.printedMrp(), "lines[" + i + "].printedMrp")
                    : null;
            parsedUnitCosts.add(unitCost);
            parsedMrps.add(printedMrp);

            receipt.addLine(new StockReceiptLine(
                    i,
                    sku,
                    poLine,
                    lineReq.quantity(),
                    unitCost,
                    printedMrp
            ));
        }

        StockReceipt savedReceipt = stockReceiptRepository.saveAndFlush(receipt);

        Map<UUID, StockBatch> batchesByReceiptLineId = new LinkedHashMap<>();
        Map<UUID, String> internalBarcodesByBatchId = new LinkedHashMap<>();

        List<StockReceiptLine> savedLines = savedReceipt.getLines();
        for (int i = 0; i < savedLines.size(); i++) {
            StockReceiptLine savedLine = savedLines.get(i);
            CreateStockReceiptLineRequest lineReq = request.lines().get(i);

            StockBatch batch = stockBatchRepository.saveAndFlush(new StockBatch(
                    savedLine.getSku(),
                    savedLine,
                    request.receivedDate(),
                    parsedUnitCosts.get(i),
                    parsedMrps.get(i)
            ));
            batchesByReceiptLineId.put(savedLine.getId(), batch);

            inventoryMovementService.recordReceipt(
                    batch,
                    savedLine.getQuantity(),
                    savedLine,
                    postedAt,
                    actorUserId
            );

            if (Boolean.TRUE.equals(lineReq.generateInternalTag())) {
                String code = String.format("BGD%08d", barcodeMappingRepository.nextInternalBarcodeSequence());
                barcodeMappingRepository.save(new BarcodeMapping(
                        code,
                        BarcodeSourceType.INTERNAL,
                        savedLine.getSku(),
                        batch,
                        actorUserId
                ));
                internalBarcodesByBatchId.put(batch.getId(), code);
            }

            if (savedLine.getPurchaseOrderLine() != null) {
                savedLine.getPurchaseOrderLine().recordReceipt(savedLine.getQuantity());
            }
        }

        if (po != null) {
            long totalOrdered = 0;
            long totalReceived = 0;
            long totalCancelled = 0;
            for (PurchaseOrderLine poLine : poLines) {
                totalOrdered += poLine.getOrderedQuantity();
                totalReceived += poLine.getReceivedQuantity();
                totalCancelled += poLine.getCancelledQuantity();
            }
            po.setStatus(PurchaseOrder.deriveStatus(totalOrdered, totalReceived, totalCancelled));
            po.touchUpdatedAt();
        }

        inventoryMovementService.incrementStockVersions(lockedStockStates);

        entityManager.flush();

        StockReceiptResponse response = toResponse(
                savedReceipt,
                vendor,
                po,
                poLines,
                batchesByReceiptLineId,
                internalBarcodesByBatchId
        );

        idempotencyService.saveResult(
                RECEIPT_OPERATION,
                idempotencyKey,
                requestHash,
                201,
                response,
                actorUserId
        );

        return response;
    }

    @Transactional(readOnly = true)
    public StockReceiptResponse getReceipt(UUID receiptId) {
        StockReceipt receipt = stockReceiptRepository.findByIdWithDetails(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Stock receipt not found"));

        List<UUID> receiptLineIds = receipt.getLines().stream()
                .map(StockReceiptLine::getId)
                .toList();

        Map<UUID, StockBatch> batchesByReceiptLineId = receiptLineIds.isEmpty()
                ? Map.of()
                : stockBatchRepository.findByStockReceiptLineIdIn(receiptLineIds).stream()
                        .collect(Collectors.toMap(b -> b.getStockReceiptLine().getId(), Function.identity()));

        List<UUID> batchIds = batchesByReceiptLineId.values().stream()
                .map(StockBatch::getId)
                .toList();

        Map<UUID, String> internalBarcodesByBatchId = batchIds.isEmpty()
                ? Map.of()
                : barcodeMappingRepository.findByBatchIdInAndSourceType(batchIds, BarcodeSourceType.INTERNAL).stream()
                        .collect(Collectors.toMap(bm -> bm.getBatch().getId(), BarcodeMapping::getCode));

        PurchaseOrder po = receipt.getPurchaseOrder();
        List<PurchaseOrderLine> poLines = po != null ? po.getLines() : List.of();

        return toResponse(
                receipt,
                receipt.getVendor(),
                po,
                poLines,
                batchesByReceiptLineId,
                internalBarcodesByBatchId
        );
    }

    private void validateLinkedReceiptLines(
            List<CreateStockReceiptLineRequest> lines,
            Map<UUID, PurchaseOrderLine> poLinesById) {
        Map<UUID, Integer> requestedPerPoLine = new LinkedHashMap<>();

        for (int i = 0; i < lines.size(); i++) {
            CreateStockReceiptLineRequest lineReq = lines.get(i);
            if (lineReq.poLineId() == null) {
                throw new PoLineMismatchException("All lines in a PO-linked receipt must specify poLineId");
            }
            PurchaseOrderLine poLine = poLinesById.get(lineReq.poLineId());
            if (poLine == null) {
                throw new PoLineMismatchException("PO line does not belong to purchase order: " + lineReq.poLineId());
            }
            if (!Objects.equals(poLine.getSku().getId(), lineReq.skuId())) {
                throw new PoLineMismatchException(
                        "Receipt line SKU does not match PO line SKU for poLineId: " + lineReq.poLineId()
                );
            }
            requestedPerPoLine.merge(lineReq.poLineId(), lineReq.quantity(), Integer::sum);
        }

        for (Map.Entry<UUID, Integer> entry : requestedPerPoLine.entrySet()) {
            PurchaseOrderLine poLine = poLinesById.get(entry.getKey());
            int openQty = poLine.getOpenQuantity();
            if (entry.getValue() > openQty) {
                throw new PoQuantityExceededException(
                        "Requested quantity " + entry.getValue() + " exceeds open quantity " + openQty
                                + " for PO line " + entry.getKey()
                );
            }
        }
    }

    private void validateDirectReceiptLines(List<CreateStockReceiptLineRequest> lines) {
        for (CreateStockReceiptLineRequest lineReq : lines) {
            if (lineReq.poLineId() != null) {
                throw new PoLineMismatchException("poLineId must not be set on a direct receipt without poId");
            }
        }
    }

    private Map<String, Object> buildCanonicalRequest(
            CreateStockReceiptRequest request,
            String normalizedInvoiceRef) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("vendorId", request.vendorId().toString());
        canonical.put("receivedDate", request.receivedDate().toString());
        canonical.put("poId", request.poId() != null ? request.poId().toString() : null);
        canonical.put("vendorInvoiceRef", normalizedInvoiceRef);

        List<Map<String, Object>> lineList = new ArrayList<>(request.lines().size());
        for (CreateStockReceiptLineRequest line : request.lines()) {
            Map<String, Object> lineMap = new LinkedHashMap<>();
            lineMap.put("skuId", line.skuId().toString());
            lineMap.put("quantity", line.quantity());
            lineMap.put("unitCost", line.unitCost() != null ? line.unitCost().trim() : null);
            lineMap.put("printedMrp", line.printedMrp() != null ? line.printedMrp().trim() : null);
            lineMap.put("poLineId", line.poLineId() != null ? line.poLineId().toString() : null);
            lineMap.put("generateInternalTag", Boolean.TRUE.equals(line.generateInternalTag()));
            lineList.add(lineMap);
        }
        canonical.put("lines", lineList);
        return canonical;
    }

    private StockReceiptResponse toResponse(
            StockReceipt receipt,
            Vendor vendor,
            PurchaseOrder po,
            List<PurchaseOrderLine> poLines,
            Map<UUID, StockBatch> batchesByReceiptLineId,
            Map<UUID, String> internalBarcodesByBatchId) {

        List<StockReceiptLineResponse> lineResponses = new ArrayList<>(receipt.getLines().size());
        for (StockReceiptLine line : receipt.getLines()) {
            StockBatch batch = batchesByReceiptLineId.get(line.getId());
            Sku sku = line.getSku();
            PurchaseOrderLine poLine = line.getPurchaseOrderLine();

            lineResponses.add(new StockReceiptLineResponse(
                    line.getId(),
                    sku.getId(),
                    sku.getSkuCode(),
                    sku.getProduct().getName(),
                    sku.getSize(),
                    poLine != null ? poLine.getId() : null,
                    poLine != null ? poLine.getOpenQuantity() : null,
                    batch.getId(),
                    line.getQuantity(),
                    CatalogValues.formatPrice(line.getUnitCost()),
                    CatalogValues.formatPrice(line.getPrintedMrp()),
                    internalBarcodesByBatchId.get(batch.getId())
            ));
        }

        Long poTotalOpen = null;
        if (po != null) {
            long open = 0;
            for (PurchaseOrderLine poLine : poLines) {
                open += poLine.getOpenQuantity();
            }
            poTotalOpen = open;
        }

        return new StockReceiptResponse(
                receipt.getId(),
                receipt.getReceiptNumber(),
                vendor.getId(),
                vendor.getName(),
                po != null ? po.getId() : null,
                po != null ? po.getPoNumber() : null,
                po != null ? po.getStatus() : null,
                poTotalOpen,
                po != null ? po.getVersion() : null,
                receipt.getVendorInvoiceRef(),
                receipt.getReceivedDate(),
                receipt.getPostedAt(),
                lineResponses
        );
    }
}
