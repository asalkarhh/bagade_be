package com.bagade.reporting.service;

import com.bagade.billing.entity.BillingEventType;
import com.bagade.billing.entity.PaymentDirection;
import com.bagade.billing.entity.PaymentMode;
import com.bagade.billing.entity.Customer;
import com.bagade.billing.repository.CustomerRepository;
import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.util.PaginationHelper;
import com.bagade.common.util.ReportingDateHelper;
import com.bagade.common.util.ReportingDateHelper.PriceRange;
import com.bagade.inventory.entity.StockCondition;
import com.bagade.reporting.dto.CollectionsReportResponse;
import com.bagade.reporting.dto.CustomerCollectionRowResponse;
import com.bagade.reporting.dto.SalesReportItemResponse;
import com.bagade.reporting.dto.SalesReportResponse;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class SalesAndCollectionsReportService {

    private final CategoryRepository categoryRepository;
    private final CustomerRepository customerRepository;
    private final ZoneId shopZone;

    @PersistenceContext
    private EntityManager entityManager;

    public SalesAndCollectionsReportService(
            CategoryRepository categoryRepository,
            CustomerRepository customerRepository,
            @Value("${app.shop.timezone:Asia/Kolkata}") String shopTimezone) {
        this.categoryRepository = categoryRepository;
        this.customerRepository = customerRepository;
        this.shopZone = ZoneId.of(shopTimezone);
    }

    private record BillLineKey(UUID billId, UUID logicalLineId) {}

    private static final class SkuSalesAcc {
        final UUID skuId;
        final String skuCode;
        final String productName;
        final String size;
        long netUnitsSold = 0;
        BigDecimal netSales = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal cogs = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        boolean costComplete = true;
        long nonSellableReturnUnits = 0;
        BigDecimal nonSellableReturnCost = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        boolean nonSellableCostComplete = true;
        BigDecimal nonSellableReturnSalesValue = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

        SkuSalesAcc(UUID skuId, String skuCode, String productName, String size) {
            this.skuId = skuId;
            this.skuCode = skuCode;
            this.productName = productName;
            this.size = size;
        }
    }

    private static final class CustomerCollectionAcc {
        final UUID customerId;
        BigDecimal cashCollected = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal upiCollected = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal cashRefunded = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal upiRefunded = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal creditSales = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal cumBillTotalAsOfEnd = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal cumNetPaidAsOfEnd = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

        CustomerCollectionAcc(UUID customerId) {
            this.customerId = customerId;
        }

        BigDecimal totalCollections() {
            return cashCollected.add(upiCollected);
        }

        BigDecimal totalRefunds() {
            return cashRefunded.add(upiRefunded);
        }

        BigDecimal netCollections() {
            return totalCollections().subtract(totalRefunds());
        }

        BigDecimal outstandingBalance() {
            return cumBillTotalAsOfEnd.subtract(cumNetPaidAsOfEnd);
        }
    }

    @Transactional(readOnly = true)
    public SalesReportResponse getSalesReport(
            LocalDate from,
            LocalDate to,
            UUID categoryId,
            UUID skuId,
            String rawMinPrice,
            String rawMaxPrice,
            int page,
            int size) {

        ReportingDateHelper.validateDateRange(from, to);
        PriceRange priceRange = ReportingDateHelper.parseAndValidatePriceRange(rawMinPrice, rawMaxPrice);
        Pageable pageable = PaginationHelper.createPageable(page, size, Sort.unsorted());

        List<UUID> categoryIds = categoryId != null ? categoryRepository.findDescendantCategoryIds(categoryId) : null;
        if (categoryIds != null && categoryIds.isEmpty()) {
            boolean unfilteredShop = isUnfiltered(categoryId, skuId, priceRange);
            String expenses = unfilteredShop ? CatalogValues.formatPrice(queryExpensesTotal(from, to)) : null;
            BigDecimal estProfit = unfilteredShop ? BigDecimal.ZERO.setScale(2).subtract(new BigDecimal(expenses)) : null;
            return new SalesReportResponse(
                    from, to, categoryId, skuId,
                    CatalogValues.formatPrice(priceRange.minPrice()), CatalogValues.formatPrice(priceRange.maxPrice()),
                    0L, "0.00", "0.00", "0.00", true,
                    0L, "0.00", "0.00",
                    expenses, CatalogValues.formatPrice(estProfit),
                    List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0L
            );
        }

        Instant start = ReportingDateHelper.startInclusive(from, shopZone);
        Instant end = ReportingDateHelper.endExclusive(to, shopZone);

        // 1) Signed billing event lines in [start, end)
        List<Object[]> eventLineRows = queryBillingEventLines(start, end, categoryIds, skuId);
        // 2) Allocations created in [start, end)
        List<Object[]> allocationRows = queryPeriodAllocations(start, end, categoryIds, skuId, priceRange);
        // 3) Reversals posted in [start, end)
        List<Object[]> reversalRows = queryPeriodReversals(start, end, categoryIds, skuId);

        // Batch-load historical BillLine unitSellingPrice lookup for involved bills
        Set<UUID> billIds = new HashSet<>();
        for (Object[] r : eventLineRows) {
            billIds.add((UUID) r[0]);
        }
        for (Object[] r : reversalRows) {
            billIds.add((UUID) r[10]);
        }
        Map<BillLineKey, TreeMap<Integer, BigDecimal>> unitPriceHistory = loadBillLineUnitPriceHistory(billIds);

        Map<UUID, SkuSalesAcc> accBySku = new LinkedHashMap<>();

        for (Object[] row : eventLineRows) {
            UUID billId = (UUID) row[0];
            int eventRevNumber = ((Number) row[1]).intValue();
            UUID logicalLineId = (UUID) row[2];
            UUID rowSkuId = (UUID) row[3];
            String skuCode = (String) row[4];
            String productName = (String) row[5];
            String rowSize = (String) row[6];
            int qtyDelta = ((Number) row[8]).intValue();
            BigDecimal totalDelta = (BigDecimal) row[9];

            BigDecimal historicalUnitPrice = resolveHistoricalPrice(unitPriceHistory, billId, logicalLineId, eventRevNumber);
            if (!matchesPriceFilter(historicalUnitPrice, priceRange)) {
                continue;
            }

            SkuSalesAcc acc = accBySku.computeIfAbsent(rowSkuId,
                    ignored -> new SkuSalesAcc(rowSkuId, skuCode, productName, rowSize));
            acc.netUnitsSold += qtyDelta;
            acc.netSales = acc.netSales.add(totalDelta);
        }

        for (Object[] row : allocationRows) {
            UUID rowSkuId = (UUID) row[0];
            String skuCode = (String) row[1];
            String productName = (String) row[2];
            String rowSize = (String) row[3];
            int qty = ((Number) row[5]).intValue();
            BigDecimal unitCost = (BigDecimal) row[6];

            SkuSalesAcc acc = accBySku.computeIfAbsent(rowSkuId,
                    ignored -> new SkuSalesAcc(rowSkuId, skuCode, productName, rowSize));
            if (unitCost == null) {
                acc.costComplete = false;
            } else {
                acc.cogs = acc.cogs.add(unitCost.multiply(BigDecimal.valueOf(qty)));
            }
        }

        for (Object[] row : reversalRows) {
            UUID rowSkuId = (UUID) row[0];
            String skuCode = (String) row[1];
            String productName = (String) row[2];
            String rowSize = (String) row[3];
            BigDecimal blUnitSellingPrice = (BigDecimal) row[4];
            BigDecimal blLineTotal = (BigDecimal) row[5];
            int blQuantity = ((Number) row[6]).intValue();
            int revQty = ((Number) row[7]).intValue();
            BigDecimal unitCost = (BigDecimal) row[8];
            StockCondition disposition = (StockCondition) row[9];
            UUID billId = (UUID) row[10];
            UUID logicalLineId = (UUID) row[11];
            int eventRevNumber = ((Number) row[12]).intValue();

            BigDecimal historicalUnitPrice = resolveHistoricalPrice(unitPriceHistory, billId, logicalLineId, eventRevNumber);
            if (historicalUnitPrice == null) {
                historicalUnitPrice = blUnitSellingPrice;
            }
            if (!matchesPriceFilter(historicalUnitPrice, priceRange)) {
                continue;
            }

            SkuSalesAcc acc = accBySku.computeIfAbsent(rowSkuId,
                    ignored -> new SkuSalesAcc(rowSkuId, skuCode, productName, rowSize));

            if (disposition == StockCondition.SELLABLE) {
                if (unitCost == null) {
                    acc.costComplete = false;
                } else {
                    acc.cogs = acc.cogs.subtract(unitCost.multiply(BigDecimal.valueOf(revQty)));
                }
            } else {
                // Non-sellable return keeps cost unrecovered in COGS and exposes damaged return value separately
                acc.nonSellableReturnUnits += revQty;
                BigDecimal proportionalSalesVal = blLineTotal
                        .multiply(BigDecimal.valueOf(revQty))
                        .divide(BigDecimal.valueOf(blQuantity), 2, RoundingMode.HALF_UP);
                acc.nonSellableReturnSalesValue = acc.nonSellableReturnSalesValue.add(proportionalSalesVal);
                if (unitCost == null) {
                    acc.nonSellableCostComplete = false;
                } else {
                    acc.nonSellableReturnCost = acc.nonSellableReturnCost.add(unitCost.multiply(BigDecimal.valueOf(revQty)));
                }
            }
        }

        List<SkuSalesAcc> sorted = new ArrayList<>(accBySku.values());
        sorted.sort(Comparator.comparing((SkuSalesAcc a) -> a.productName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(a -> a.skuCode, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(a -> a.skuId));

        long totalUnits = 0L;
        BigDecimal totalNetSales = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal totalCogs = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        boolean allCostComplete = true;
        long totalNonSellableUnits = 0L;
        BigDecimal totalNonSellableCost = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        boolean allNonSellableCostComplete = true;
        BigDecimal totalNonSellableSalesValue = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

        List<SalesReportItemResponse> allItems = new ArrayList<>(sorted.size());
        for (SkuSalesAcc acc : sorted) {
            totalUnits += acc.netUnitsSold;
            totalNetSales = totalNetSales.add(acc.netSales);
            if (!acc.costComplete) {
                allCostComplete = false;
            } else {
                totalCogs = totalCogs.add(acc.cogs);
            }
            totalNonSellableUnits += acc.nonSellableReturnUnits;
            totalNonSellableSalesValue = totalNonSellableSalesValue.add(acc.nonSellableReturnSalesValue);
            if (!acc.nonSellableCostComplete) {
                allNonSellableCostComplete = false;
            } else {
                totalNonSellableCost = totalNonSellableCost.add(acc.nonSellableReturnCost);
            }

            BigDecimal rowGrossProfit = acc.costComplete ? acc.netSales.subtract(acc.cogs) : null;
            allItems.add(new SalesReportItemResponse(
                    acc.skuId,
                    acc.skuCode,
                    acc.productName,
                    acc.size,
                    acc.netUnitsSold,
                    CatalogValues.formatPrice(acc.netSales),
                    acc.costComplete ? CatalogValues.formatPrice(acc.cogs) : null,
                    rowGrossProfit != null ? CatalogValues.formatPrice(rowGrossProfit) : null,
                    acc.costComplete,
                    acc.nonSellableReturnUnits,
                    acc.nonSellableCostComplete ? CatalogValues.formatPrice(acc.nonSellableReturnCost) : null,
                    CatalogValues.formatPrice(acc.nonSellableReturnSalesValue)
            ));
        }

        BigDecimal totalGrossProfit = allCostComplete ? totalNetSales.subtract(totalCogs) : null;
        boolean unfilteredShop = isUnfiltered(categoryId, skuId, priceRange);
        BigDecimal expensesTotal = unfilteredShop ? queryExpensesTotal(from, to) : null;
        BigDecimal estimatedProfit = (unfilteredShop && totalGrossProfit != null)
                ? totalGrossProfit.subtract(expensesTotal)
                : null;

        List<SalesReportItemResponse> pageItems = slicePage(allItems, pageable);

        return new SalesReportResponse(
                from,
                to,
                categoryId,
                skuId,
                CatalogValues.formatPrice(priceRange.minPrice()),
                CatalogValues.formatPrice(priceRange.maxPrice()),
                totalUnits,
                CatalogValues.formatPrice(totalNetSales),
                allCostComplete ? CatalogValues.formatPrice(totalCogs) : null,
                totalGrossProfit != null ? CatalogValues.formatPrice(totalGrossProfit) : null,
                allCostComplete,
                totalNonSellableUnits,
                allNonSellableCostComplete ? CatalogValues.formatPrice(totalNonSellableCost) : null,
                CatalogValues.formatPrice(totalNonSellableSalesValue),
                expensesTotal != null ? CatalogValues.formatPrice(expensesTotal) : null,
                estimatedProfit != null ? CatalogValues.formatPrice(estimatedProfit) : null,
                pageItems,
                pageable.getPageNumber(),
                pageable.getPageSize(),
                allItems.size()
        );
    }

    @Transactional(readOnly = true)
    public CollectionsReportResponse getCollectionsReport(
            LocalDate from,
            LocalDate to,
            UUID customerId,
            int page,
            int size) {

        ReportingDateHelper.validateDateRange(from, to);
        Pageable pageable = PaginationHelper.createPageable(page, size, Sort.unsorted());
        Instant start = ReportingDateHelper.startInclusive(from, shopZone);
        Instant end = ReportingDateHelper.endExclusive(to, shopZone);

        CustomerCollectionAcc shopTotal = new CustomerCollectionAcc(null);
        Map<UUID, CustomerCollectionAcc> byCustomer = new LinkedHashMap<>();

        // 1) Collections & refunds in [start, end)
        List<Object[]> paymentRows = entityManager.createQuery("""
                        SELECT c.id, p.direction, p.mode, SUM(p.amount)
                        FROM PaymentEntry p
                        JOIN p.bill b
                        LEFT JOIN b.customer c
                        WHERE p.postedAt >= :start AND p.postedAt < :end
                          AND (:customerId IS NULL OR c.id = :customerId)
                        GROUP BY c.id, p.direction, p.mode
                        """, Object[].class)
                .setParameter("start", start)
                .setParameter("end", end)
                .setParameter("customerId", customerId)
                .getResultList();

        for (Object[] r : paymentRows) {
            UUID custId = (UUID) r[0];
            PaymentDirection dir = (PaymentDirection) r[1];
            PaymentMode mode = (PaymentMode) r[2];
            BigDecimal sum = (BigDecimal) r[3];

            applyPaymentAmount(shopTotal, dir, mode, sum);
            if (custId != null) {
                CustomerCollectionAcc cAcc = byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new);
                applyPaymentAmount(cAcc, dir, mode, sum);
            }
        }

        // 2) Credit sales generated in [start, end) = (ISSUE totals + SalesAdjustments) - same-event settlements
        List<Object[]> issuePeriodRows = queryIssueTotalsGroupedByCustomer(start, end, customerId);
        List<Object[]> adjPeriodRows = querySalesAdjustmentsGroupedByCustomer(start, end, customerId);
        List<Object[]> eventSettlementPeriodRows = queryEventSettlementsGroupedByCustomer(start, end, customerId);

        for (Object[] r : issuePeriodRows) {
            UUID custId = (UUID) r[0];
            BigDecimal sum = (BigDecimal) r[1];
            shopTotal.creditSales = shopTotal.creditSales.add(sum);
            if (custId != null) {
                byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new).creditSales =
                        byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new).creditSales.add(sum);
            }
        }
        for (Object[] r : adjPeriodRows) {
            UUID custId = (UUID) r[0];
            BigDecimal sum = (BigDecimal) r[1];
            shopTotal.creditSales = shopTotal.creditSales.add(sum);
            if (custId != null) {
                byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new).creditSales =
                        byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new).creditSales.add(sum);
            }
        }
        for (Object[] r : eventSettlementPeriodRows) {
            UUID custId = (UUID) r[0];
            BigDecimal netSettled = (BigDecimal) r[1];
            shopTotal.creditSales = shopTotal.creditSales.subtract(netSettled);
            if (custId != null) {
                byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new).creditSales =
                        byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new).creditSales.subtract(netSettled);
            }
        }

        // 3) Historical outstanding balance strictly through end of 'to' (< end)
        for (Object[] r : queryIssueTotalsGroupedByCustomer(null, end, customerId)) {
            UUID custId = (UUID) r[0];
            BigDecimal sum = (BigDecimal) r[1];
            shopTotal.cumBillTotalAsOfEnd = shopTotal.cumBillTotalAsOfEnd.add(sum);
            if (custId != null) {
                byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new).cumBillTotalAsOfEnd =
                        byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new).cumBillTotalAsOfEnd.add(sum);
            }
        }
        for (Object[] r : querySalesAdjustmentsGroupedByCustomer(null, end, customerId)) {
            UUID custId = (UUID) r[0];
            BigDecimal sum = (BigDecimal) r[1];
            shopTotal.cumBillTotalAsOfEnd = shopTotal.cumBillTotalAsOfEnd.add(sum);
            if (custId != null) {
                byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new).cumBillTotalAsOfEnd =
                        byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new).cumBillTotalAsOfEnd.add(sum);
            }
        }
        for (Object[] r : entityManager.createQuery("""
                        SELECT c.id,
                               SUM(CASE WHEN p.direction = com.bagade.billing.entity.PaymentDirection.PAYMENT
                                        THEN p.amount ELSE -p.amount END)
                        FROM PaymentEntry p
                        JOIN p.bill b
                        LEFT JOIN b.customer c
                        WHERE p.postedAt < :end
                          AND (:customerId IS NULL OR c.id = :customerId)
                        GROUP BY c.id
                        """, Object[].class)
                .setParameter("end", end)
                .setParameter("customerId", customerId)
                .getResultList()) {
            UUID custId = (UUID) r[0];
            BigDecimal netPaid = (BigDecimal) r[1];
            shopTotal.cumNetPaidAsOfEnd = shopTotal.cumNetPaidAsOfEnd.add(netPaid);
            if (custId != null) {
                byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new).cumNetPaidAsOfEnd =
                        byCustomer.computeIfAbsent(custId, CustomerCollectionAcc::new).cumNetPaidAsOfEnd.add(netPaid);
            }
        }

        Map<UUID, Customer> customersById = byCustomer.isEmpty()
                ? Map.of()
                : customerRepository.findAllById(byCustomer.keySet()).stream()
                        .collect(java.util.stream.Collectors.toMap(Customer::getId, java.util.function.Function.identity()));

        List<CustomerCollectionAcc> activeCustomers = byCustomer.values().stream()
                .filter(c -> c.totalCollections().compareTo(BigDecimal.ZERO) != 0
                        || c.totalRefunds().compareTo(BigDecimal.ZERO) != 0
                        || c.creditSales.compareTo(BigDecimal.ZERO) != 0
                        || c.outstandingBalance().compareTo(BigDecimal.ZERO) != 0)
                .sorted(Comparator
                        .comparing((CustomerCollectionAcc a) -> {
                            Customer c = customersById.get(a.customerId);
                            return c != null ? c.getName() : "";
                        }, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(a -> a.customerId))
                .toList();

        List<CustomerCollectionRowResponse> allRows = new ArrayList<>(activeCustomers.size());
        for (CustomerCollectionAcc acc : activeCustomers) {
            Customer c = customersById.get(acc.customerId);
            allRows.add(new CustomerCollectionRowResponse(
                    acc.customerId,
                    c != null ? c.getName() : null,
                    c != null ? c.getContact() : null,
                    CatalogValues.formatPrice(acc.cashCollected),
                    CatalogValues.formatPrice(acc.upiCollected),
                    CatalogValues.formatPrice(acc.totalCollections()),
                    CatalogValues.formatPrice(acc.totalRefunds()),
                    CatalogValues.formatPrice(acc.netCollections()),
                    CatalogValues.formatPrice(acc.creditSales),
                    CatalogValues.formatPrice(acc.outstandingBalance())
            ));
        }

        List<CustomerCollectionRowResponse> pageItems = slicePage(allRows, pageable);

        return new CollectionsReportResponse(
                from,
                to,
                customerId,
                CatalogValues.formatPrice(shopTotal.cashCollected),
                CatalogValues.formatPrice(shopTotal.upiCollected),
                CatalogValues.formatPrice(shopTotal.totalCollections()),
                CatalogValues.formatPrice(shopTotal.cashRefunded),
                CatalogValues.formatPrice(shopTotal.upiRefunded),
                CatalogValues.formatPrice(shopTotal.totalRefunds()),
                CatalogValues.formatPrice(shopTotal.netCollections()),
                CatalogValues.formatPrice(shopTotal.creditSales),
                CatalogValues.formatPrice(shopTotal.outstandingBalance()),
                pageItems,
                pageable.getPageNumber(),
                pageable.getPageSize(),
                allRows.size()
        );
    }

    private void applyPaymentAmount(
            CustomerCollectionAcc acc,
            PaymentDirection dir,
            PaymentMode mode,
            BigDecimal amount) {
        if (dir == PaymentDirection.PAYMENT) {
            if (mode == PaymentMode.CASH) {
                acc.cashCollected = acc.cashCollected.add(amount);
            } else {
                acc.upiCollected = acc.upiCollected.add(amount);
            }
        } else {
            if (mode == PaymentMode.CASH) {
                acc.cashRefunded = acc.cashRefunded.add(amount);
            } else {
                acc.upiRefunded = acc.upiRefunded.add(amount);
            }
        }
    }

    private List<Object[]> queryBillingEventLines(
            Instant start,
            Instant end,
            List<UUID> categoryIds,
            UUID skuId) {
        StringBuilder jpql = new StringBuilder("""
                SELECT ev.bill.id, br.revisionNumber, el.logicalLineId,
                       s.id, s.skuCode, p.name, s.size, p.category.id,
                       el.quantityDelta, el.lineTotalDelta
                FROM BillingEventLine el
                JOIN el.billingEvent ev
                JOIN ev.billRevision br
                JOIN el.sku s
                JOIN s.product p
                WHERE ev.occurredAt >= :start AND ev.occurredAt < :end
                """);
        if (categoryIds != null) {
            jpql.append(" AND p.category.id IN :categoryIds");
        }
        if (skuId != null) {
            jpql.append(" AND s.id = :skuId");
        }
        TypedQuery<Object[]> q = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("start", start)
                .setParameter("end", end);
        if (categoryIds != null) {
            q.setParameter("categoryIds", categoryIds);
        }
        if (skuId != null) {
            q.setParameter("skuId", skuId);
        }
        return q.getResultList();
    }

    private List<Object[]> queryPeriodAllocations(
            Instant start,
            Instant end,
            List<UUID> categoryIds,
            UUID skuId,
            PriceRange priceRange) {
        StringBuilder jpql = new StringBuilder("""
                SELECT s.id, s.skuCode, p.name, s.size, bl.unitSellingPrice,
                       sa.quantity, sa.capturedUnitCost, br.bill.id, bl.logicalLineId
                FROM SaleAllocation sa
                JOIN sa.billLine bl
                JOIN bl.billRevision br
                JOIN sa.sku s
                JOIN s.product p
                WHERE br.createdAt >= :start AND br.createdAt < :end
                """);
        if (categoryIds != null) {
            jpql.append(" AND p.category.id IN :categoryIds");
        }
        if (skuId != null) {
            jpql.append(" AND s.id = :skuId");
        }
        if (priceRange.minPrice() != null) {
            jpql.append(" AND bl.unitSellingPrice >= :minPrice");
        }
        if (priceRange.maxPrice() != null) {
            jpql.append(" AND bl.unitSellingPrice <= :maxPrice");
        }
        TypedQuery<Object[]> q = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("start", start)
                .setParameter("end", end);
        if (categoryIds != null) {
            q.setParameter("categoryIds", categoryIds);
        }
        if (skuId != null) {
            q.setParameter("skuId", skuId);
        }
        if (priceRange.minPrice() != null) {
            q.setParameter("minPrice", priceRange.minPrice());
        }
        if (priceRange.maxPrice() != null) {
            q.setParameter("maxPrice", priceRange.maxPrice());
        }
        return q.getResultList();
    }

    private List<Object[]> queryPeriodReversals(
            Instant start,
            Instant end,
            List<UUID> categoryIds,
            UUID skuId) {
        StringBuilder jpql = new StringBuilder("""
                SELECT s.id, s.skuCode, p.name, s.size, bl.unitSellingPrice,
                       bl.lineTotal, bl.quantity, r.quantity, r.capturedUnitCost, r.disposition,
                       be.bill.id, bl.logicalLineId, be.billRevision.revisionNumber
                FROM SaleAllocationReversal r
                JOIN r.billingEvent be
                JOIN r.saleAllocation sa
                JOIN sa.billLine bl
                JOIN r.sku s
                JOIN s.product p
                WHERE be.occurredAt >= :start AND be.occurredAt < :end
                """);
        if (categoryIds != null) {
            jpql.append(" AND p.category.id IN :categoryIds");
        }
        if (skuId != null) {
            jpql.append(" AND s.id = :skuId");
        }
        TypedQuery<Object[]> q = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("start", start)
                .setParameter("end", end);
        if (categoryIds != null) {
            q.setParameter("categoryIds", categoryIds);
        }
        if (skuId != null) {
            q.setParameter("skuId", skuId);
        }
        return q.getResultList();
    }

    private Map<BillLineKey, TreeMap<Integer, BigDecimal>> loadBillLineUnitPriceHistory(Set<UUID> billIds) {
        Map<BillLineKey, TreeMap<Integer, BigDecimal>> map = new HashMap<>();
        if (billIds.isEmpty()) {
            return map;
        }
        List<Object[]> rows = entityManager.createQuery("""
                        SELECT br.bill.id, br.revisionNumber, bl.logicalLineId, bl.unitSellingPrice
                        FROM BillLine bl
                        JOIN bl.billRevision br
                        WHERE br.bill.id IN :billIds
                        ORDER BY br.revisionNumber ASC
                        """, Object[].class)
                .setParameter("billIds", billIds)
                .getResultList();
        for (Object[] r : rows) {
            UUID billId = (UUID) r[0];
            int rev = ((Number) r[1]).intValue();
            UUID logicalLineId = (UUID) r[2];
            BigDecimal unitPrice = (BigDecimal) r[3];
            map.computeIfAbsent(new BillLineKey(billId, logicalLineId), ignored -> new TreeMap<>())
                    .put(rev, unitPrice);
        }
        return map;
    }

    private BigDecimal resolveHistoricalPrice(
            Map<BillLineKey, TreeMap<Integer, BigDecimal>> history,
            UUID billId,
            UUID logicalLineId,
            int revNumber) {
        TreeMap<Integer, BigDecimal> byRev = history.get(new BillLineKey(billId, logicalLineId));
        if (byRev == null) {
            return null;
        }
        var entry = byRev.floorEntry(revNumber);
        if (entry != null) {
            return entry.getValue();
        }
        return byRev.firstEntry() != null ? byRev.firstEntry().getValue() : null;
    }

    private boolean matchesPriceFilter(BigDecimal unitPrice, PriceRange range) {
        if (range.minPrice() == null && range.maxPrice() == null) {
            return true;
        }
        if (unitPrice == null) {
            return false;
        }
        if (range.minPrice() != null && unitPrice.compareTo(range.minPrice()) < 0) {
            return false;
        }
        return range.maxPrice() == null || unitPrice.compareTo(range.maxPrice()) <= 0;
    }

    private boolean isUnfiltered(UUID categoryId, UUID skuId, PriceRange priceRange) {
        return categoryId == null && skuId == null && priceRange.minPrice() == null && priceRange.maxPrice() == null;
    }

    private BigDecimal queryExpensesTotal(LocalDate from, LocalDate to) {
        BigDecimal total = entityManager.createQuery("""
                        SELECT COALESCE(SUM(e.amount), 0)
                        FROM Expense e
                        WHERE e.expenseDate >= :fromDate AND e.expenseDate <= :toDate
                        """, BigDecimal.class)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .getSingleResult();
        return total != null ? total.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
    }

    private List<Object[]> queryIssueTotalsGroupedByCustomer(Instant start, Instant end, UUID customerId) {
        StringBuilder jpql = new StringBuilder("""
                SELECT c.id, SUM(br.total)
                FROM BillingEvent ev
                JOIN ev.bill b
                LEFT JOIN b.customer c
                JOIN ev.billRevision br
                WHERE ev.eventType = com.bagade.billing.entity.BillingEventType.ISSUE
                  AND ev.occurredAt < :end
                  AND (:customerId IS NULL OR c.id = :customerId)
                """);
        if (start != null) {
            jpql.append(" AND ev.occurredAt >= :start");
        }
        jpql.append(" GROUP BY c.id");

        TypedQuery<Object[]> q = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("end", end)
                .setParameter("customerId", customerId);
        if (start != null) {
            q.setParameter("start", start);
        }
        return q.getResultList();
    }

    private List<Object[]> querySalesAdjustmentsGroupedByCustomer(Instant start, Instant end, UUID customerId) {
        StringBuilder jpql = new StringBuilder("""
                SELECT c.id, SUM(sa.amountDelta)
                FROM SalesAdjustment sa
                JOIN sa.bill b
                LEFT JOIN b.customer c
                WHERE sa.occurredAt < :end
                  AND (:customerId IS NULL OR c.id = :customerId)
                """);
        if (start != null) {
            jpql.append(" AND sa.occurredAt >= :start");
        }
        jpql.append(" GROUP BY c.id");

        TypedQuery<Object[]> q = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("end", end)
                .setParameter("customerId", customerId);
        if (start != null) {
            q.setParameter("start", start);
        }
        return q.getResultList();
    }

    private List<Object[]> queryEventSettlementsGroupedByCustomer(Instant start, Instant end, UUID customerId) {
        return entityManager.createQuery("""
                        SELECT c.id,
                               SUM(CASE WHEN p.direction = com.bagade.billing.entity.PaymentDirection.PAYMENT
                                        THEN p.amount ELSE -p.amount END)
                        FROM PaymentEntry p
                        JOIN p.billingEvent ev
                        JOIN ev.bill b
                        LEFT JOIN b.customer c
                        WHERE ev.occurredAt >= :start AND ev.occurredAt < :end
                          AND (:customerId IS NULL OR c.id = :customerId)
                        GROUP BY c.id
                        """, Object[].class)
                .setParameter("start", start)
                .setParameter("end", end)
                .setParameter("customerId", customerId)
                .getResultList();
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
