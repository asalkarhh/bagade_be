package com.bagade.reporting.service;

import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.error.RequestValidationException;
import com.bagade.common.util.PaginationHelper;
import com.bagade.common.util.ReportingDateHelper;
import com.bagade.inventory.entity.BatchSource;
import com.bagade.inventory.entity.StockCondition;
import com.bagade.inventory.entity.StockCountScope;
import com.bagade.reporting.dto.StockAgeingReportResponse;
import com.bagade.reporting.dto.StockAgeingRowResponse;
import com.bagade.reporting.dto.StockAuditReportResponse;
import com.bagade.reporting.dto.StockAuditRowResponse;
import com.bagade.reporting.dto.VendorPerformanceRowResponse;
import com.bagade.reporting.dto.VendorReportResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class VendorAndStockReportService {

    private static final String UNKNOWN_VENDOR_LABEL = "Unknown Vendor (Audit Surplus)";

    private final CategoryRepository categoryRepository;
    private final ZoneId shopZone;

    @PersistenceContext
    private EntityManager entityManager;

    public VendorAndStockReportService(
            CategoryRepository categoryRepository,
            @Value("${app.shop.timezone:Asia/Kolkata}") String shopTimezone) {
        this.categoryRepository = categoryRepository;
        this.shopZone = ZoneId.of(shopTimezone);
    }

    private record VendorCohortKey(UUID vendorId, String vendorName, String batchSource) {}

    private record BillLineKey(UUID billId, UUID logicalLineId) {}

    private record EffectiveBillLine(int quantity, BigDecimal lineTotal) {}

    private static final class VendorCohortAcc {
        final VendorCohortKey key;
        long receivedUnits = 0L;
        long directBatchSoldUnits = 0L;
        long fifoSoldUnits = 0L;
        long remainingSellableUnits = 0L;
        BigDecimal allocatedRevenue = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal allocatedCogs = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        boolean costComplete = true;
        long weightedDaysNumerator = 0L;
        long weightedDaysDenominator = 0L;

        VendorCohortAcc(VendorCohortKey key) {
            this.key = key;
        }

        long netSoldUnits() {
            return directBatchSoldUnits + fifoSoldUnits;
        }
    }

    @Transactional(readOnly = true)
    public VendorReportResponse getVendorReport(
            LocalDate from,
            LocalDate to,
            LocalDate requestedSnapshotDate,
            UUID vendorId,
            UUID categoryId,
            int page,
            int size) {

        ReportingDateHelper.validateDateRange(from, to);
        LocalDate snapshotDate = requestedSnapshotDate != null ? requestedSnapshotDate : LocalDate.now(shopZone);
        if (snapshotDate.isBefore(from)) {
            throw new RequestValidationException("snapshotDate", "snapshotDate must not be before 'from' date");
        }
        Pageable pageable = PaginationHelper.createPageable(page, size, Sort.unsorted());

        List<UUID> categoryIds = categoryId != null ? categoryRepository.findDescendantCategoryIds(categoryId) : null;
        if (categoryIds != null && categoryIds.isEmpty()) {
            return emptyVendorReport(from, to, snapshotDate, vendorId, categoryId, pageable);
        }

        List<Object[]> cohortBatchRows = queryCohortBatches(from, to, vendorId, categoryIds);
        if (cohortBatchRows.isEmpty()) {
            return emptyVendorReport(from, to, snapshotDate, vendorId, categoryId, pageable);
        }

        Map<UUID, VendorCohortKey> cohortKeyByBatchId = new HashMap<>();
        Map<UUID, LocalDate> receiptDateByBatchId = new HashMap<>();
        Map<VendorCohortKey, VendorCohortAcc> accByCohort = new LinkedHashMap<>();

        for (Object[] row : cohortBatchRows) {
            UUID batchId = (UUID) row[0];
            BatchSource source = (BatchSource) row[1];
            LocalDate receivedDate = (LocalDate) row[2];
            UUID rowVendorId = (UUID) row[3];
            String vendorName = (String) row[4];
            Number receiptQty = (Number) row[5];
            Number countedQty = (Number) row[6];
            Number expectedQty = (Number) row[7];

            long batchReceivedUnits = 0L;
            if (source == BatchSource.AUDIT_SURPLUS) {
                if (countedQty != null && expectedQty != null) {
                    batchReceivedUnits = countedQty.longValue() - expectedQty.longValue();
                }
            } else if (receiptQty != null) {
                batchReceivedUnits = receiptQty.longValue();
            }

            VendorCohortKey key = (source == BatchSource.AUDIT_SURPLUS || rowVendorId == null)
                    ? new VendorCohortKey(null, UNKNOWN_VENDOR_LABEL, "AUDIT_SURPLUS")
                    : new VendorCohortKey(rowVendorId, vendorName, "VENDOR");

            cohortKeyByBatchId.put(batchId, key);
            receiptDateByBatchId.put(batchId, receivedDate);
            accByCohort.computeIfAbsent(key, VendorCohortAcc::new).receivedUnits += batchReceivedUnits;
        }

        Instant snapshotEnd = ReportingDateHelper.endExclusive(snapshotDate, shopZone);
        Set<UUID> cohortBatchIds = cohortKeyByBatchId.keySet();

        // Remaining sellable quantity as of snapshotEnd from immutable StockMovement ledger
        List<Object[]> balanceSums = entityManager.createQuery("""
                        SELECT m.batch.id, SUM(m.quantityDelta)
                        FROM StockMovement m
                        WHERE m.batch.id IN :batchIds
                          AND m.stockCondition = com.bagade.inventory.entity.StockCondition.SELLABLE
                          AND m.postedAt < :snapshotEnd
                        GROUP BY m.batch.id
                        """, Object[].class)
                .setParameter("batchIds", cohortBatchIds)
                .setParameter("snapshotEnd", snapshotEnd)
                .getResultList();

        for (Object[] r : balanceSums) {
            UUID batchId = (UUID) r[0];
            long rem = ((Number) r[1]).longValue();
            VendorCohortAcc acc = accByCohort.get(cohortKeyByBatchId.get(batchId));
            acc.remainingSellableUnits += Math.max(0L, rem);
        }

        // Sale allocations for cohort batches through snapshotEnd
        List<Object[]> allocRows = entityManager.createQuery("""
                        SELECT sa.id, sa.batch.id, sa.quantity, sa.capturedUnitCost, sa.attributionMethod,
                               br.bill.id, bl.logicalLineId, br.createdAt, bl.quantity, bl.lineTotal
                        FROM SaleAllocation sa
                        JOIN sa.billLine bl
                        JOIN bl.billRevision br
                        WHERE sa.batch.id IN :batchIds
                          AND br.createdAt < :snapshotEnd
                        """, Object[].class)
                .setParameter("batchIds", cohortBatchIds)
                .setParameter("snapshotEnd", snapshotEnd)
                .getResultList();

        if (!allocRows.isEmpty()) {
            Set<UUID> allocIds = new HashSet<>();
            Set<UUID> billIds = new HashSet<>();
            for (Object[] r : allocRows) {
                allocIds.add((UUID) r[0]);
                billIds.add((UUID) r[5]);
            }

            Map<BillLineKey, EffectiveBillLine> latestBillLines = loadLatestBillLinesAsOf(billIds, snapshotEnd);
            Map<UUID, Long> sellableReversedByAlloc = new HashMap<>();
            Map<UUID, Long> nonSellableReversedByAlloc = new HashMap<>();

            List<Object[]> revRows = entityManager.createQuery("""
                            SELECT r.saleAllocation.id, r.disposition, SUM(r.quantity)
                            FROM SaleAllocationReversal r
                            JOIN r.billingEvent be
                            WHERE r.saleAllocation.id IN :allocIds
                              AND be.occurredAt < :snapshotEnd
                            GROUP BY r.saleAllocation.id, r.disposition
                            """, Object[].class)
                    .setParameter("allocIds", allocIds)
                    .setParameter("snapshotEnd", snapshotEnd)
                    .getResultList();

            for (Object[] r : revRows) {
                UUID allocId = (UUID) r[0];
                StockCondition disp = (StockCondition) r[1];
                long qty = ((Number) r[2]).longValue();
                if (disp == StockCondition.SELLABLE) {
                    sellableReversedByAlloc.merge(allocId, qty, Long::sum);
                } else {
                    nonSellableReversedByAlloc.merge(allocId, qty, Long::sum);
                }
            }

            for (Object[] r : allocRows) {
                UUID allocId = (UUID) r[0];
                UUID batchId = (UUID) r[1];
                long allocatedQty = ((Number) r[2]).longValue();
                BigDecimal unitCost = (BigDecimal) r[3];
                com.bagade.billing.entity.AllocationAttributionMethod method =
                        (com.bagade.billing.entity.AllocationAttributionMethod) r[4];
                UUID billId = (UUID) r[5];
                UUID logicalLineId = (UUID) r[6];
                Instant saleAt = (Instant) r[7];
                int origLineQty = ((Number) r[8]).intValue();
                BigDecimal origLineTotal = (BigDecimal) r[9];

                long sellableRev = sellableReversedByAlloc.getOrDefault(allocId, 0L);
                long nonSellableRev = nonSellableReversedByAlloc.getOrDefault(allocId, 0L);
                long netSoldQty = Math.max(0L, allocatedQty - sellableRev - nonSellableRev);
                long costUnits = Math.max(0L, allocatedQty - sellableRev);

                VendorCohortAcc acc = accByCohort.get(cohortKeyByBatchId.get(batchId));
                if (method == com.bagade.billing.entity.AllocationAttributionMethod.EXPLICIT_BATCH) {
                    acc.directBatchSoldUnits += netSoldQty;
                } else {
                    acc.fifoSoldUnits += netSoldQty;
                }

                if (unitCost == null && costUnits > 0) {
                    acc.costComplete = false;
                } else if (unitCost != null) {
                    acc.allocatedCogs = acc.allocatedCogs.add(unitCost.multiply(BigDecimal.valueOf(costUnits)));
                }

                EffectiveBillLine effectiveLine = latestBillLines.getOrDefault(
                        new BillLineKey(billId, logicalLineId),
                        new EffectiveBillLine(origLineQty, origLineTotal)
                );
                if (netSoldQty > 0 && effectiveLine.quantity() > 0) {
                    BigDecimal allocRev = effectiveLine.lineTotal()
                            .multiply(BigDecimal.valueOf(netSoldQty))
                            .divide(BigDecimal.valueOf(effectiveLine.quantity()), 2, RoundingMode.HALF_UP);
                    acc.allocatedRevenue = acc.allocatedRevenue.add(allocRev);
                }

                LocalDate receiptDate = receiptDateByBatchId.get(batchId);
                if (netSoldQty > 0 && receiptDate != null) {
                    LocalDate saleDate = LocalDate.ofInstant(saleAt, shopZone);
                    long days = Math.max(0L, ChronoUnit.DAYS.between(receiptDate, saleDate));
                    acc.weightedDaysNumerator += days * netSoldQty;
                    acc.weightedDaysDenominator += netSoldQty;
                }
            }
        }

        List<VendorCohortAcc> sorted = new ArrayList<>(accByCohort.values());
        sorted.sort(Comparator
                .comparing((VendorCohortAcc a) -> "AUDIT_SURPLUS".equals(a.key.batchSource()) ? 1 : 0)
                .thenComparing(a -> a.key.vendorName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(a -> a.key.vendorId(), Comparator.nullsLast(Comparator.naturalOrder())));

        long totalReceived = 0L;
        long totalDirect = 0L;
        long totalFifo = 0L;
        long totalRemaining = 0L;
        BigDecimal totalRevenue = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal totalCogs = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        boolean allCostComplete = true;
        long totalDaysNum = 0L;
        long totalDaysDen = 0L;

        List<VendorPerformanceRowResponse> allRows = new ArrayList<>(sorted.size());
        for (VendorCohortAcc acc : sorted) {
            long netSold = acc.netSoldUnits();
            totalReceived += acc.receivedUnits;
            totalDirect += acc.directBatchSoldUnits;
            totalFifo += acc.fifoSoldUnits;
            totalRemaining += acc.remainingSellableUnits;
            totalDaysNum += acc.weightedDaysNumerator;
            totalDaysDen += acc.weightedDaysDenominator;
            totalRevenue = totalRevenue.add(acc.allocatedRevenue);

            if (!acc.costComplete) {
                allCostComplete = false;
            } else {
                totalCogs = totalCogs.add(acc.allocatedCogs);
            }

            BigDecimal rowMargin = acc.costComplete ? acc.allocatedRevenue.subtract(acc.allocatedCogs) : null;
            allRows.add(new VendorPerformanceRowResponse(
                    acc.key.vendorId(),
                    acc.key.vendorName(),
                    acc.key.batchSource(),
                    acc.receivedUnits,
                    acc.directBatchSoldUnits,
                    acc.fifoSoldUnits,
                    netSold,
                    acc.remainingSellableUnits,
                    formatPercentage(netSold, acc.receivedUnits),
                    rowMargin != null ? CatalogValues.formatPrice(rowMargin) : null,
                    acc.costComplete,
                    formatWeightedDays(acc.weightedDaysNumerator, acc.weightedDaysDenominator)
            ));
        }

        long totalNetSold = totalDirect + totalFifo;
        BigDecimal totalMargin = allCostComplete ? totalRevenue.subtract(totalCogs) : null;
        List<VendorPerformanceRowResponse> pageItems = slicePage(allRows, pageable);

        return new VendorReportResponse(
                from,
                to,
                snapshotDate,
                vendorId,
                categoryId,
                totalReceived,
                totalDirect,
                totalFifo,
                totalNetSold,
                totalRemaining,
                formatPercentage(totalNetSold, totalReceived),
                totalMargin != null ? CatalogValues.formatPrice(totalMargin) : null,
                allCostComplete,
                formatWeightedDays(totalDaysNum, totalDaysDen),
                pageItems,
                pageable.getPageNumber(),
                pageable.getPageSize(),
                allRows.size()
        );
    }

    @Transactional(readOnly = true)
    public StockAgeingReportResponse getStockAgeingReport(
            UUID categoryId,
            UUID skuId,
            UUID vendorId,
            int page,
            int size) {

        Pageable pageable = PaginationHelper.createPageable(page, size, Sort.unsorted());
        LocalDate today = LocalDate.now(shopZone);

        List<UUID> categoryIds = categoryId != null ? categoryRepository.findDescendantCategoryIds(categoryId) : null;
        if (categoryIds != null && categoryIds.isEmpty()) {
            return new StockAgeingReportResponse(today, 0L, "0.00", true, 0L, 0L, List.of(),
                    pageable.getPageNumber(), pageable.getPageSize(), 0L);
        }

        StringBuilder jpql = new StringBuilder("""
                SELECT b.id, s.id, s.skuCode, p.name, s.size,
                       b.batchSource, v.id, v.name, b.receivedDate,
                       bb.quantity, b.unitCost
                FROM BatchBalance bb
                JOIN bb.batch b
                JOIN b.sku s
                JOIN s.product p
                LEFT JOIN b.stockReceiptLine rl
                LEFT JOIN rl.stockReceipt sr
                LEFT JOIN sr.vendor v
                WHERE bb.stockCondition = com.bagade.inventory.entity.StockCondition.SELLABLE
                  AND bb.quantity > 0
                """);
        if (categoryIds != null) {
            jpql.append(" AND p.category.id IN :categoryIds");
        }
        if (skuId != null) {
            jpql.append(" AND s.id = :skuId");
        }
        if (vendorId != null) {
            jpql.append(" AND v.id = :vendorId");
        }

        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class);
        if (categoryIds != null) {
            query.setParameter("categoryIds", categoryIds);
        }
        if (skuId != null) {
            query.setParameter("skuId", skuId);
        }
        if (vendorId != null) {
            query.setParameter("vendorId", vendorId);
        }

        List<Object[]> rawRows = query.getResultList();

        long totalSellable = 0L;
        BigDecimal totalValue = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        boolean costComplete = true;
        long unknownDateUnits = 0L;
        long unknownCostUnits = 0L;

        List<StockAgeingRowResponse> allRows = new ArrayList<>(rawRows.size());
        for (Object[] r : rawRows) {
            UUID batchId = (UUID) r[0];
            UUID rowSkuId = (UUID) r[1];
            String skuCode = (String) r[2];
            String productName = (String) r[3];
            String rowSize = (String) r[4];
            BatchSource source = (BatchSource) r[5];
            UUID rowVendorId = (UUID) r[6];
            String vendorName = (String) r[7];
            LocalDate dbReceivedDate = (LocalDate) r[8];
            long qty = ((Number) r[9]).longValue();
            BigDecimal unitCost = (BigDecimal) r[10];

            // Audit surplus batches do not have a vendor receipt date
            boolean receiptDateKnown = (source == BatchSource.RECEIPT && dbReceivedDate != null);
            LocalDate reportedDate = receiptDateKnown ? dbReceivedDate : null;
            Long daysOnHand = receiptDateKnown ? Math.max(0L, ChronoUnit.DAYS.between(reportedDate, today)) : null;

            boolean costKnown = (unitCost != null);
            BigDecimal stockValue = costKnown
                    ? unitCost.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP)
                    : null;

            totalSellable += qty;
            if (!receiptDateKnown) {
                unknownDateUnits += qty;
            }
            if (!costKnown) {
                costComplete = false;
                unknownCostUnits += qty;
            } else {
                totalValue = totalValue.add(stockValue);
            }

            allRows.add(new StockAgeingRowResponse(
                    batchId,
                    rowSkuId,
                    skuCode,
                    productName,
                    rowSize,
                    source.name(),
                    rowVendorId,
                    vendorName != null ? vendorName : UNKNOWN_VENDOR_LABEL,
                    reportedDate,
                    receiptDateKnown,
                    daysOnHand,
                    qty,
                    costKnown ? CatalogValues.formatPrice(unitCost) : null,
                    costKnown,
                    stockValue != null ? CatalogValues.formatPrice(stockValue) : null
            ));
        }

        // Oldest batches first, unknown receipt dates at the end, then product/SKU/batchId
        allRows.sort(Comparator
                .comparing(StockAgeingRowResponse::receiptDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(StockAgeingRowResponse::productName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(StockAgeingRowResponse::skuCode, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(StockAgeingRowResponse::batchId));

        List<StockAgeingRowResponse> pageItems = slicePage(allRows, pageable);

        return new StockAgeingReportResponse(
                today,
                totalSellable,
                costComplete ? CatalogValues.formatPrice(totalValue) : null,
                costComplete,
                unknownDateUnits,
                unknownCostUnits,
                pageItems,
                pageable.getPageNumber(),
                pageable.getPageSize(),
                allRows.size()
        );
    }

    @Transactional(readOnly = true)
    public StockAuditReportResponse getStockAuditReport(
            LocalDate from,
            LocalDate to,
            UUID categoryId,
            UUID skuId,
            int page,
            int size) {

        ReportingDateHelper.validateDateRange(from, to);
        Pageable pageable = PaginationHelper.createPageable(page, size, Sort.unsorted());

        List<UUID> categoryIds = categoryId != null ? categoryRepository.findDescendantCategoryIds(categoryId) : null;
        if (categoryIds != null && categoryIds.isEmpty()) {
            return new StockAuditReportResponse(from, to, 0L, 0L, 0L, List.of(),
                    pageable.getPageNumber(), pageable.getPageSize(), 0L);
        }

        Instant start = ReportingDateHelper.startInclusive(from, shopZone);
        Instant end = ReportingDateHelper.endExclusive(to, shopZone);

        StringBuilder jpql = new StringBuilder("""
                SELECT sc.id, l.id, sc.scope, s.id, s.skuCode, p.name, s.size,
                       l.expectedQuantity, l.countedQuantity, l.reason,
                       u.id, u.username, u.displayName, sc.postedAt
                FROM StockCountLine l
                JOIN l.stockCount sc
                JOIN l.sku s
                JOIN s.product p
                JOIN UserAccount u ON u.id = sc.postedBy
                WHERE sc.status = com.bagade.inventory.entity.StockCountStatus.POSTED
                  AND sc.postedAt >= :start
                  AND sc.postedAt < :end
                """);
        if (categoryIds != null) {
            jpql.append(" AND p.category.id IN :categoryIds");
        }
        if (skuId != null) {
            jpql.append(" AND s.id = :skuId");
        }
        jpql.append(" ORDER BY sc.postedAt DESC, p.name ASC, s.skuCode ASC, l.id ASC");

        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("start", start)
                .setParameter("end", end);
        if (categoryIds != null) {
            query.setParameter("categoryIds", categoryIds);
        }
        if (skuId != null) {
            query.setParameter("skuId", skuId);
        }

        List<Object[]> rows = query.getResultList();

        long posAdj = 0L;
        long negAdj = 0L;
        List<StockAuditRowResponse> allRows = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            int expected = ((Number) r[7]).intValue();
            int counted = ((Number) r[8]).intValue();
            int adj = counted - expected;
            if (adj > 0) {
                posAdj += adj;
            } else if (adj < 0) {
                negAdj += -adj;
            }
            allRows.add(new StockAuditRowResponse(
                    (UUID) r[0],
                    (UUID) r[1],
                    (StockCountScope) r[2],
                    (UUID) r[3],
                    (String) r[4],
                    (String) r[5],
                    (String) r[6],
                    expected,
                    counted,
                    adj,
                    (String) r[9],
                    (UUID) r[10],
                    (String) r[11],
                    (String) r[12],
                    (Instant) r[13]
            ));
        }

        List<StockAuditRowResponse> pageItems = slicePage(allRows, pageable);
        return new StockAuditReportResponse(
                from,
                to,
                posAdj,
                negAdj,
                posAdj - negAdj,
                pageItems,
                pageable.getPageNumber(),
                pageable.getPageSize(),
                allRows.size()
        );
    }

    private List<Object[]> queryCohortBatches(
            LocalDate from,
            LocalDate to,
            UUID vendorId,
            List<UUID> categoryIds) {
        StringBuilder jpql = new StringBuilder("""
                SELECT b.id, b.batchSource, b.receivedDate,
                       v.id, v.name,
                       rl.quantity, scLine.countedQuantity, scLine.expectedQuantity
                FROM StockBatch b
                JOIN b.sku s
                JOIN s.product p
                LEFT JOIN b.stockReceiptLine rl
                LEFT JOIN rl.stockReceipt sr
                LEFT JOIN sr.vendor v
                LEFT JOIN b.sourceStockCountLine scLine
                WHERE b.receivedDate >= :fromDate AND b.receivedDate <= :toDate
                """);
        if (vendorId != null) {
            jpql.append(" AND v.id = :vendorId");
        }
        if (categoryIds != null) {
            jpql.append(" AND p.category.id IN :categoryIds");
        }
        TypedQuery<Object[]> q = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("fromDate", from)
                .setParameter("toDate", to);
        if (vendorId != null) {
            q.setParameter("vendorId", vendorId);
        }
        if (categoryIds != null) {
            q.setParameter("categoryIds", categoryIds);
        }
        return q.getResultList();
    }

    private Map<BillLineKey, EffectiveBillLine> loadLatestBillLinesAsOf(Set<UUID> billIds, Instant snapshotEnd) {
        Map<BillLineKey, EffectiveBillLine> result = new HashMap<>();
        if (billIds.isEmpty()) {
            return result;
        }
        List<Object[]> rows = entityManager.createQuery("""
                        SELECT br.bill.id, bl.logicalLineId, br.revisionNumber, bl.quantity, bl.lineTotal
                        FROM BillLine bl
                        JOIN bl.billRevision br
                        WHERE br.bill.id IN :billIds
                          AND br.createdAt < :snapshotEnd
                        ORDER BY br.revisionNumber ASC
                        """, Object[].class)
                .setParameter("billIds", billIds)
                .setParameter("snapshotEnd", snapshotEnd)
                .getResultList();
        for (Object[] r : rows) {
            result.put(
                    new BillLineKey((UUID) r[0], (UUID) r[1]),
                    new EffectiveBillLine(((Number) r[3]).intValue(), (BigDecimal) r[4])
            );
        }
        return result;
    }

    private VendorReportResponse emptyVendorReport(
            LocalDate from, LocalDate to, LocalDate snapshotDate, UUID vendorId, UUID categoryId, Pageable pageable) {
        return new VendorReportResponse(
                from, to, snapshotDate, vendorId, categoryId,
                0L, 0L, 0L, 0L, 0L,
                null, "0.00", true, null,
                List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0L
        );
    }

    private String formatPercentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private String formatWeightedDays(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private <T> List<T> slicePage(List<T> all, Pageable pageable) {
        int startIdx = (int) pageable.getOffset();
        if (startIdx >= all.size()) {
            return List.of();
        }
        int endIdx = Math.min(all.size(), startIdx + pageable.getPageSize());
        return all.subList(startIdx, endIdx);
    }
}
