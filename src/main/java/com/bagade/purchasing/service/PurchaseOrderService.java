package com.bagade.purchasing.service;

import com.bagade.catalog.entity.Sku;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.dto.PagedResponse;
import com.bagade.common.error.FieldErrorItem;
import com.bagade.common.error.RequestValidationException;
import com.bagade.common.error.ResourceNotFoundException;
import com.bagade.common.error.StaleVersionException;
import com.bagade.common.idempotency.IdempotencyService;
import com.bagade.common.util.PaginationHelper;
import com.bagade.purchasing.dto.CancelPurchaseOrderRemainderRequest;
import com.bagade.purchasing.dto.CreatePurchaseOrderLineRequest;
import com.bagade.purchasing.dto.CreatePurchaseOrderRequest;
import com.bagade.purchasing.dto.PurchaseOrderLineResponse;
import com.bagade.purchasing.dto.PurchaseOrderListItemResponse;
import com.bagade.purchasing.dto.PurchaseOrderResponse;
import com.bagade.purchasing.entity.PurchaseOrder;
import com.bagade.purchasing.entity.PurchaseOrderCancellation;
import com.bagade.purchasing.entity.PurchaseOrderLine;
import com.bagade.purchasing.entity.PurchaseOrderStatus;
import com.bagade.purchasing.entity.Vendor;
import com.bagade.purchasing.repository.PurchaseOrderCancellationRepository;
import com.bagade.purchasing.repository.PurchaseOrderLineRepository;
import com.bagade.purchasing.repository.PurchaseOrderListRow;
import com.bagade.purchasing.repository.PurchaseOrderRepository;
import com.bagade.purchasing.repository.VendorRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PurchaseOrderService {

    private static final String CANCEL_REMAINDER_OPERATION = "PO_CANCEL_REMAINDER";

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final PurchaseOrderCancellationRepository cancellationRepository;
    private final VendorRepository vendorRepository;
    private final SkuRepository skuRepository;
    private final IdempotencyService idempotencyService;

    @PersistenceContext
    private EntityManager entityManager;

    public PurchaseOrderService(
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderLineRepository purchaseOrderLineRepository,
            PurchaseOrderCancellationRepository cancellationRepository,
            VendorRepository vendorRepository,
            SkuRepository skuRepository,
            IdempotencyService idempotencyService) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.cancellationRepository = cancellationRepository;
        this.vendorRepository = vendorRepository;
        this.skuRepository = skuRepository;
        this.idempotencyService = idempotencyService;
    }

    @Transactional(readOnly = true)
    public PagedResponse<PurchaseOrderListItemResponse> listPurchaseOrders(
            UUID vendorId,
            PurchaseOrderStatus status,
            LocalDate from,
            LocalDate to,
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
        Page<PurchaseOrderListRow> rows = purchaseOrderRepository.searchPurchaseOrders(
                vendorId, status, from, to, pageable
        );

        Page<PurchaseOrderListItemResponse> mapped = rows.map(row -> new PurchaseOrderListItemResponse(
                row.poId(),
                row.poNumber(),
                row.vendorId(),
                row.orderDate(),
                row.status(),
                row.totalOpenQuantity() != null ? row.totalOpenQuantity() : 0L,
                row.version() != null ? row.version() : 0L
        ));

        return PagedResponse.from(mapped);
    }

    @Transactional
    public PurchaseOrderResponse createPurchaseOrder(CreatePurchaseOrderRequest request, UUID actorUserId) {
        Vendor vendor = vendorRepository.findById(request.vendorId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found"));

        if (!vendor.isActive()) {
            throw new RequestValidationException("vendorId", "Vendor is inactive");
        }

        Set<UUID> requestedSkuIds = new LinkedHashSet<>();
        for (CreatePurchaseOrderLineRequest lineReq : request.lines()) {
            requestedSkuIds.add(lineReq.skuId());
        }

        Map<UUID, Sku> skusById = skuRepository.findAllByIdWithProduct(requestedSkuIds).stream()
                .collect(Collectors.toMap(Sku::getId, Function.identity()));

        List<FieldErrorItem> lineErrors = new ArrayList<>();
        for (int i = 0; i < request.lines().size(); i++) {
            CreatePurchaseOrderLineRequest lineReq = request.lines().get(i);
            Sku sku = skusById.get(lineReq.skuId());
            if (sku == null) {
                throw new ResourceNotFoundException("SKU not found: " + lineReq.skuId());
            }
            if (!sku.isActive()) {
                lineErrors.add(new FieldErrorItem(
                        "lines[" + i + "].skuId",
                        "SKU is inactive: " + sku.getSkuCode()
                ));
            }
        }
        if (!lineErrors.isEmpty()) {
            throw new RequestValidationException("Validation failed", lineErrors);
        }

        long sequenceNumber = purchaseOrderRepository.nextPoNumberSequence();
        String poNumber = String.format("PO-%06d", sequenceNumber);

        PurchaseOrder po = new PurchaseOrder(poNumber, vendor, request.orderDate(), actorUserId);

        for (int i = 0; i < request.lines().size(); i++) {
            CreatePurchaseOrderLineRequest lineReq = request.lines().get(i);
            Sku sku = skusById.get(lineReq.skuId());
            BigDecimal agreedCost = lineReq.agreedUnitCost() != null
                    ? CatalogValues.parsePrice(lineReq.agreedUnitCost(), "lines[" + i + "].agreedUnitCost")
                    : null;
            po.addLine(new PurchaseOrderLine(sku, i, lineReq.orderedQuantity(), agreedCost));
        }

        po.recalculateStatus();
        PurchaseOrder saved = purchaseOrderRepository.saveAndFlush(po);

        return toDetailResponse(saved, vendor, saved.getLines(), skusById);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse getPurchaseOrder(UUID poId) {
        PurchaseOrder po = purchaseOrderRepository.findByIdWithDetails(poId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found"));

        Map<UUID, Sku> skusById = po.getLines().stream()
                .map(PurchaseOrderLine::getSku)
                .collect(Collectors.toMap(Sku::getId, Function.identity(), (a, b) -> a));

        return toDetailResponse(po, po.getVendor(), po.getLines(), skusById);
    }

    @Transactional
    public PurchaseOrderResponse cancelRemainder(
            UUID poId,
            String rawIdempotencyKey,
            CancelPurchaseOrderRemainderRequest request,
            UUID actorUserId) {

        String idempotencyKey = idempotencyService.validateKey(rawIdempotencyKey);
        validatePoLineIdsInput(request.poLineIds());

        String normalizedReason = CatalogValues.normalizeRequiredText(request.reason());
        String requestHash = idempotencyService.computeRequestHash(
                buildCancelRequestCanonical(poId, request.expectedVersion(), normalizedReason, request.poLineIds())
        );

        var fastReplay = idempotencyService.findReplay(
                CANCEL_REMAINDER_OPERATION, idempotencyKey, requestHash, PurchaseOrderResponse.class);
        if (fastReplay.isPresent()) {
            return fastReplay.get().body();
        }

        PurchaseOrder po = purchaseOrderRepository.findByIdForUpdate(poId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found"));

        var postLockReplay = idempotencyService.findReplay(
                CANCEL_REMAINDER_OPERATION, idempotencyKey, requestHash, PurchaseOrderResponse.class);
        if (postLockReplay.isPresent()) {
            return postLockReplay.get().body();
        }

        if (!Objects.equals(po.getVersion(), request.expectedVersion())) {
            throw new StaleVersionException("Purchase order was modified by another request");
        }

        List<PurchaseOrderLine> allLines = purchaseOrderLineRepository.findByPurchaseOrderIdForUpdate(poId);
        List<PurchaseOrderLine> targetLines = resolveTargetLines(allLines, request.poLineIds());

        PurchaseOrderCancellation cancellation = new PurchaseOrderCancellation(po.getId(), actorUserId, normalizedReason);
        for (PurchaseOrderLine line : targetLines) {
            int cancelledNow = line.cancelOpenQuantity();
            if (cancelledNow > 0) {
                cancellation.addAffectedLine(line.getId(), cancelledNow);
            }
        }

        if (cancellation.getAffectedLines().isEmpty()) {
            throw new RequestValidationException("poLineIds", "No remaining open quantities to cancel");
        }

        long totalOrdered = 0;
        long totalReceived = 0;
        long totalCancelled = 0;
        for (PurchaseOrderLine line : allLines) {
            totalOrdered += line.getOrderedQuantity();
            totalReceived += line.getReceivedQuantity();
            totalCancelled += line.getCancelledQuantity();
        }
        PurchaseOrderStatus newStatus = PurchaseOrder.deriveStatus(totalOrdered, totalReceived, totalCancelled);
        po.setStatus(newStatus);
        po.touchUpdatedAt();

        entityManager.flush();
        cancellationRepository.save(cancellation);

        Vendor vendor = vendorRepository.findById(po.getVendor().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found"));
        Set<UUID> skuIds = allLines.stream()
                .map(line -> line.getSku().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, Sku> skusById = skuRepository.findAllByIdWithProduct(skuIds).stream()
                .collect(Collectors.toMap(Sku::getId, Function.identity()));

        PurchaseOrderResponse response = toDetailResponse(po, vendor, allLines, skusById);
        idempotencyService.saveResult(
                CANCEL_REMAINDER_OPERATION,
                idempotencyKey,
                requestHash,
                200,
                response,
                actorUserId
        );

        return response;
    }

    private void validatePoLineIdsInput(List<UUID> poLineIds) {
        if (poLineIds == null) {
            return;
        }
        if (poLineIds.isEmpty()) {
            throw new RequestValidationException("poLineIds", "poLineIds must not be empty when provided");
        }
        for (UUID id : poLineIds) {
            if (id == null) {
                throw new RequestValidationException("poLineIds", "poLineIds must not contain null entries");
            }
        }
    }

    private List<PurchaseOrderLine> resolveTargetLines(List<PurchaseOrderLine> allLines, List<UUID> requestedIds) {
        if (requestedIds == null) {
            return allLines;
        }
        Map<UUID, PurchaseOrderLine> byId = new LinkedHashMap<>();
        for (PurchaseOrderLine line : allLines) {
            byId.put(line.getId(), line);
        }

        Set<UUID> deduplicatedIds = new LinkedHashSet<>(requestedIds);
        List<PurchaseOrderLine> targets = new ArrayList<>(deduplicatedIds.size());
        for (UUID lineId : deduplicatedIds) {
            PurchaseOrderLine match = byId.get(lineId);
            if (match == null) {
                throw new RequestValidationException(
                        "poLineIds",
                        "PO line ID does not belong to this purchase order: " + lineId
                );
            }
            targets.add(match);
        }
        return targets;
    }

    private Map<String, Object> buildCancelRequestCanonical(
            UUID poId,
            Long expectedVersion,
            String normalizedReason,
            List<UUID> poLineIds) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("poId", poId.toString());
        canonical.put("expectedVersion", expectedVersion);
        canonical.put("reason", normalizedReason);
        canonical.put("poLineIds", poLineIds != null
                ? poLineIds.stream().map(UUID::toString).toList()
                : null);
        return canonical;
    }

    private PurchaseOrderResponse toDetailResponse(
            PurchaseOrder po,
            Vendor vendor,
            List<PurchaseOrderLine> lines,
            Map<UUID, Sku> skusById) {

        List<PurchaseOrderLineResponse> lineResponses = new ArrayList<>(lines.size());
        long totalOpenQuantity = 0;

        for (PurchaseOrderLine line : lines) {
            Sku sku = skusById.get(line.getSku().getId());
            int openQty = line.getOpenQuantity();
            totalOpenQuantity += openQty;

            lineResponses.add(new PurchaseOrderLineResponse(
                    line.getId(),
                    sku.getId(),
                    sku.getSkuCode(),
                    sku.getProduct().getName(),
                    sku.getSize(),
                    line.getOrderedQuantity(),
                    line.getReceivedQuantity(),
                    line.getCancelledQuantity(),
                    openQty,
                    CatalogValues.formatPrice(line.getAgreedUnitCost())
            ));
        }

        return new PurchaseOrderResponse(
                po.getId(),
                po.getPoNumber(),
                vendor.getId(),
                vendor.getName(),
                po.getOrderDate(),
                po.getStatus(),
                totalOpenQuantity,
                po.getVersion() != null ? po.getVersion() : 0L,
                lineResponses
        );
    }
}
