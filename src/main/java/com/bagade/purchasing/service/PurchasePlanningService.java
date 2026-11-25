package com.bagade.purchasing.service;

import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.error.ResourceNotFoundException;
import com.bagade.common.util.PaginationHelper;
import com.bagade.common.util.ReportingDateHelper;
import com.bagade.common.util.ReportingDateHelper.PriceRange;
import com.bagade.inventory.entity.StockCountStatus;
import com.bagade.inventory.repository.BatchBalanceRepository;
import com.bagade.purchasing.dto.PastVendorRefResponse;
import com.bagade.purchasing.dto.PurchasePlanningItemResponse;
import com.bagade.purchasing.dto.PurchasePlanningResponse;
import com.bagade.purchasing.dto.PurchaseTargetResponse;
import com.bagade.purchasing.dto.UpsertPurchaseTargetRequest;
import com.bagade.purchasing.entity.PurchaseTarget;
import com.bagade.purchasing.repository.PurchaseTargetRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PurchasePlanningService {

    private static final int RECENT_SALES_DAYS = 30;

    private final SkuRepository skuRepository;
    private final CategoryRepository categoryRepository;
    private final BatchBalanceRepository batchBalanceRepository;
    private final PurchaseTargetRepository purchaseTargetRepository;
    private final ZoneId shopZone;

    @PersistenceContext
    private EntityManager entityManager;

    public PurchasePlanningService(
            SkuRepository skuRepository,
            CategoryRepository categoryRepository,
            BatchBalanceRepository batchBalanceRepository,
            PurchaseTargetRepository purchaseTargetRepository,
            @Value("${app.shop.timezone:Asia/Kolkata}") String shopTimezone) {
        this.skuRepository = skuRepository;
        this.categoryRepository = categoryRepository;
        this.batchBalanceRepository = batchBalanceRepository;
        this.purchaseTargetRepository = purchaseTargetRepository;
        this.shopZone = ZoneId.of(shopTimezone);
    }

    @Transactional
    public PurchaseTargetResponse upsertTarget(
            UUID skuId,
            UpsertPurchaseTargetRequest request,
            UUID actorUserId) {
        if (!skuRepository.existsById(skuId)) {
            throw new ResourceNotFoundException("SKU not found");
        }
        String normalizedPeriod = CatalogValues.normalizeOptionalText(request.planningPeriod());
        Instant now = Instant.now();

        if (normalizedPeriod == null) {
            purchaseTargetRepository.upsertDefaultTarget(skuId, request.targetQuantity(), now, actorUserId);
        } else {
            purchaseTargetRepository.upsertPeriodTarget(
                    skuId, normalizedPeriod, request.targetQuantity(), now, actorUserId
            );
        }

        return new PurchaseTargetResponse(skuId, request.targetQuantity(), normalizedPeriod);
    }

    @Transactional(readOnly = true)
    public PurchasePlanningResponse getPurchasePlanning(
            UUID categoryId,
            String rawSize,
            String rawMinPrice,
            String rawMaxPrice,
            UUID vendorId,
            String rawPlanningPeriod,
            int page,
            int pageSize) {

        PriceRange priceRange = ReportingDateHelper.parseAndValidatePriceRange(rawMinPrice, rawMaxPrice);
        Pageable pageable = PaginationHelper.createPageable(page, pageSize, Sort.unsorted());
        String normalizedSize = CatalogValues.normalizeOptionalText(rawSize);
        String planningPeriod = CatalogValues.normalizeOptionalText(rawPlanningPeriod);

        LocalDate recentSalesTo = LocalDate.now(shopZone);
        LocalDate recentSalesFrom = recentSalesTo.minusDays(RECENT_SALES_DAYS - 1L);

        List<UUID> categoryIds = categoryId != null ? categoryRepository.findDescendantCategoryIds(categoryId) : null;
        if (categoryIds != null && categoryIds.isEmpty()) {
            return new PurchasePlanningResponse(
                    recentSalesFrom, recentSalesTo, planningPeriod,
                    List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0L
            );
        }

        long total = countMatchingSkus(categoryIds, normalizedSize, priceRange, vendorId);
        if (total == 0 || pageable.getOffset() >= total) {
            return new PurchasePlanningResponse(
                    recentSalesFrom, recentSalesTo, planningPeriod,
                    List.of(), pageable.getPageNumber(), pageable.getPageSize(), total
            );
        }

        List<Object[]> skuRows = fetchMatchingSkusPage(categoryIds, normalizedSize, priceRange, vendorId, pageable);
        List<UUID> pageSkuIds = skuRows.stream().map(r -> (UUID) r[0]).toList();

        Map<UUID, Long> sellableBySku = loadSellableBySku(pageSkuIds);
        Map<UUID, Long> openPoBySku = loadOpenPoBySku(pageSkuIds);
        Map<UUID, Long> recentSalesBySku = loadRecentSalesBySku(
                pageSkuIds,
                ReportingDateHelper.startInclusive(recentSalesFrom, shopZone),
                ReportingDateHelper.endExclusive(recentSalesTo, shopZone)
        );
        Map<UUID, LocalDate> lastCountDateBySku = loadLastVerifiedCountDates(pageSkuIds);
        Map<UUID, List<PastVendorRefResponse>> pastVendorsBySku = loadPastVendors(pageSkuIds);
        Map<UUID, ResolvedTarget> targetBySku = loadResolvedTargets(pageSkuIds, planningPeriod);

        List<PurchasePlanningItemResponse> items = new ArrayList<>(skuRows.size());
        for (Object[] r : skuRows) {
            UUID skuId = (UUID) r[0];
            String skuCode = (String) r[1];
            String productName = (String) r[2];
            String size = (String) r[3];
            BigDecimal currentPrice = (BigDecimal) r[4];

            long sellableQty = sellableBySku.getOrDefault(skuId, 0L);
            long openPoQty = openPoBySku.getOrDefault(skuId, 0L);
            long recentSales = recentSalesBySku.getOrDefault(skuId, 0L);
            ResolvedTarget target = targetBySku.get(skuId);

            Integer targetQuantity = target != null ? target.targetQuantity() : null;
            String resolvedPeriod = target != null ? target.planningPeriod() : null;
            Long suggestedQty = targetQuantity != null
                    ? Math.max(0L, targetQuantity.longValue() - sellableQty - openPoQty)
                    : null;

            items.add(new PurchasePlanningItemResponse(
                    skuId,
                    skuCode,
                    productName,
                    size,
                    CatalogValues.formatPrice(currentPrice),
                    sellableQty,
                    openPoQty,
                    recentSales,
                    lastCountDateBySku.get(skuId),
                    pastVendorsBySku.getOrDefault(skuId, List.of()),
                    targetQuantity,
                    resolvedPeriod,
                    suggestedQty
            ));
        }

        return new PurchasePlanningResponse(
                recentSalesFrom,
                recentSalesTo,
                planningPeriod,
                items,
                pageable.getPageNumber(),
                pageable.getPageSize(),
                total
        );
    }

    private record ResolvedTarget(int targetQuantity, String planningPeriod) {}

    private long countMatchingSkus(
            List<UUID> categoryIds,
            String size,
            PriceRange priceRange,
            UUID vendorId) {
        String where = buildSkuFilterWhere(categoryIds, size, priceRange, vendorId);
        TypedQuery<Long> q = entityManager.createQuery(
                "SELECT COUNT(s.id) FROM Sku s JOIN s.product p" + where, Long.class
        );
        bindSkuFilterParams(q, categoryIds, size, priceRange, vendorId);
        return q.getSingleResult();
    }

    private List<Object[]> fetchMatchingSkusPage(
            List<UUID> categoryIds,
            String size,
            PriceRange priceRange,
            UUID vendorId,
            Pageable pageable) {
        String where = buildSkuFilterWhere(categoryIds, size, priceRange, vendorId);
        TypedQuery<Object[]> q = entityManager.createQuery(
                "SELECT s.id, s.skuCode, p.name, s.size, s.defaultSellingPrice FROM Sku s JOIN s.product p"
                        + where + " ORDER BY p.name ASC, s.skuCode ASC, s.id ASC",
                Object[].class
        );
        bindSkuFilterParams(q, categoryIds, size, priceRange, vendorId);
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        return q.getResultList();
    }

    private String buildSkuFilterWhere(
            List<UUID> categoryIds,
            String size,
            PriceRange priceRange,
            UUID vendorId) {
        StringBuilder sb = new StringBuilder(" WHERE s.active = true");
        if (categoryIds != null) {
            sb.append(" AND p.category.id IN :categoryIds");
        }
        if (size != null) {
            sb.append(" AND s.size IS NOT NULL AND LOWER(s.size) = :sizeLower");
        }
        if (priceRange.minPrice() != null) {
            sb.append(" AND s.defaultSellingPrice >= :minPrice");
        }
        if (priceRange.maxPrice() != null) {
            sb.append(" AND s.defaultSellingPrice <= :maxPrice");
        }
        if (vendorId != null) {
            sb.append(" AND (EXISTS (SELECT pol.id FROM PurchaseOrderLine pol JOIN pol.purchaseOrder po "
                    + "WHERE pol.sku.id = s.id AND po.vendor.id = :vendorId) "
                    + "OR EXISTS (SELECT srl.id FROM StockReceiptLine srl JOIN srl.stockReceipt sr "
                    + "WHERE srl.sku.id = s.id AND sr.vendor.id = :vendorId))");
        }
        return sb.toString();
    }

    private void bindSkuFilterParams(
            TypedQuery<?> q,
            List<UUID> categoryIds,
            String size,
            PriceRange priceRange,
            UUID vendorId) {
        if (categoryIds != null) {
            q.setParameter("categoryIds", categoryIds);
        }
        if (size != null) {
            q.setParameter("sizeLower", size.toLowerCase(Locale.ROOT));
        }
        if (priceRange.minPrice() != null) {
            q.setParameter("minPrice", priceRange.minPrice());
        }
        if (priceRange.maxPrice() != null) {
            q.setParameter("maxPrice", priceRange.maxPrice());
        }
        if (vendorId != null) {
            q.setParameter("vendorId", vendorId);
        }
    }

    private Map<UUID, Long> loadSellableBySku(List<UUID> skuIds) {
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] r : batchBalanceRepository.sumSellableBySkuIds(skuIds)) {
            map.put((UUID) r[0], ((Number) r[1]).longValue());
        }
        return map;
    }

    private Map<UUID, Long> loadOpenPoBySku(List<UUID> skuIds) {
        Map<UUID, Long> map = new HashMap<>();
        List<Object[]> rows = entityManager.createQuery("""
                        SELECT pol.sku.id,
                               SUM(pol.orderedQuantity - pol.receivedQuantity - pol.cancelledQuantity)
                        FROM PurchaseOrderLine pol
                        WHERE pol.sku.id IN :skuIds
                          AND (pol.orderedQuantity - pol.receivedQuantity - pol.cancelledQuantity) > 0
                        GROUP BY pol.sku.id
                        """, Object[].class)
                .setParameter("skuIds", skuIds)
                .getResultList();
        for (Object[] r : rows) {
            map.put((UUID) r[0], ((Number) r[1]).longValue());
        }
        return map;
    }

    private Map<UUID, Long> loadRecentSalesBySku(List<UUID> skuIds, Instant start, Instant end) {
        Map<UUID, Long> map = new HashMap<>();
        List<Object[]> rows = entityManager.createQuery("""
                        SELECT el.sku.id, SUM(el.quantityDelta)
                        FROM BillingEventLine el
                        JOIN el.billingEvent ev
                        WHERE el.sku.id IN :skuIds
                          AND ev.occurredAt >= :start
                          AND ev.occurredAt < :end
                        GROUP BY el.sku.id
                        """, Object[].class)
                .setParameter("skuIds", skuIds)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
        for (Object[] r : rows) {
            map.put((UUID) r[0], ((Number) r[1]).longValue());
        }
        return map;
    }

    private Map<UUID, LocalDate> loadLastVerifiedCountDates(List<UUID> skuIds) {
        Map<UUID, LocalDate> map = new HashMap<>();
        List<Object[]> rows = entityManager.createQuery("""
                        SELECT l.sku.id, MAX(sc.postedAt)
                        FROM StockCountLine l
                        JOIN l.stockCount sc
                        WHERE l.sku.id IN :skuIds
                          AND sc.status = :postedStatus
                        GROUP BY l.sku.id
                        """, Object[].class)
                .setParameter("skuIds", skuIds)
                .setParameter("postedStatus", StockCountStatus.POSTED)
                .getResultList();
        for (Object[] r : rows) {
            Instant postedAt = (Instant) r[1];
            if (postedAt != null) {
                map.put((UUID) r[0], LocalDate.ofInstant(postedAt, shopZone));
            }
        }
        return map;
    }

    private Map<UUID, List<PastVendorRefResponse>> loadPastVendors(List<UUID> skuIds) {
        Map<UUID, Map<UUID, PastVendorRefResponse>> bySku = new HashMap<>();
        List<Object[]> receiptVendors = entityManager.createQuery("""
                        SELECT DISTINCT srl.sku.id, v.id, v.name, v.active
                        FROM StockReceiptLine srl
                        JOIN srl.stockReceipt sr
                        JOIN sr.vendor v
                        WHERE srl.sku.id IN :skuIds
                        """, Object[].class)
                .setParameter("skuIds", skuIds)
                .getResultList();
        for (Object[] r : receiptVendors) {
            UUID skuId = (UUID) r[0];
            UUID vendorId = (UUID) r[1];
            bySku.computeIfAbsent(skuId, ignored -> new LinkedHashMap<>())
                    .put(vendorId, new PastVendorRefResponse(vendorId, (String) r[2], (Boolean) r[3]));
        }

        List<Object[]> poVendors = entityManager.createQuery("""
                        SELECT DISTINCT pol.sku.id, v.id, v.name, v.active
                        FROM PurchaseOrderLine pol
                        JOIN pol.purchaseOrder po
                        JOIN po.vendor v
                        WHERE pol.sku.id IN :skuIds
                        """, Object[].class)
                .setParameter("skuIds", skuIds)
                .getResultList();
        for (Object[] r : poVendors) {
            UUID skuId = (UUID) r[0];
            UUID vendorId = (UUID) r[1];
            bySku.computeIfAbsent(skuId, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(vendorId, new PastVendorRefResponse(vendorId, (String) r[2], (Boolean) r[3]));
        }

        Map<UUID, List<PastVendorRefResponse>> sorted = new HashMap<>();
        for (Map.Entry<UUID, Map<UUID, PastVendorRefResponse>> entry : bySku.entrySet()) {
            List<PastVendorRefResponse> vendors = new ArrayList<>(entry.getValue().values());
            vendors.sort(Comparator.comparing(PastVendorRefResponse::vendorName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(PastVendorRefResponse::vendorId));
            sorted.put(entry.getKey(), vendors);
        }
        return sorted;
    }

    private Map<UUID, ResolvedTarget> loadResolvedTargets(List<UUID> skuIds, String planningPeriod) {
        Map<UUID, PurchaseTarget> periodMatch = new HashMap<>();
        Map<UUID, PurchaseTarget> defaultMatch = new HashMap<>();

        for (PurchaseTarget pt : purchaseTargetRepository.findBySkuIdIn(skuIds)) {
            UUID skuId = pt.getSku().getId();
            if (pt.getPlanningPeriod() == null) {
                defaultMatch.put(skuId, pt);
            } else if (planningPeriod != null && pt.getPlanningPeriod().equalsIgnoreCase(planningPeriod)) {
                periodMatch.put(skuId, pt);
            }
        }

        Map<UUID, ResolvedTarget> resolved = new HashMap<>();
        for (UUID skuId : skuIds) {
            PurchaseTarget chosen = periodMatch.getOrDefault(skuId, defaultMatch.get(skuId));
            if (chosen != null) {
                resolved.put(skuId, new ResolvedTarget(chosen.getTargetQuantity(), chosen.getPlanningPeriod()));
            }
        }
        return resolved;
    }
}
