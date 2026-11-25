package com.bagade.reporting.controller;

import com.bagade.reporting.dto.CollectionsReportResponse;
import com.bagade.reporting.dto.SalesReportResponse;
import com.bagade.reporting.dto.StockAgeingReportResponse;
import com.bagade.reporting.dto.StockAuditReportResponse;
import com.bagade.reporting.dto.VendorReportResponse;
import com.bagade.reporting.service.SalesAndCollectionsReportService;
import com.bagade.reporting.service.VendorAndStockReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasRole('OWNER')")
public class ReportingController {

    private final SalesAndCollectionsReportService salesAndCollectionsService;
    private final VendorAndStockReportService vendorAndStockService;

    public ReportingController(
            SalesAndCollectionsReportService salesAndCollectionsService,
            VendorAndStockReportService vendorAndStockService) {
        this.salesAndCollectionsService = salesAndCollectionsService;
        this.vendorAndStockService = vendorAndStockService;
    }

    @GetMapping("/sales")
    public ResponseEntity<SalesReportResponse> getSalesReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID skuId,
            @RequestParam(required = false) String minPrice,
            @RequestParam(required = false) String maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(salesAndCollectionsService.getSalesReport(
                from, to, categoryId, skuId, minPrice, maxPrice, page, size
        ));
    }

    @GetMapping("/vendors")
    public ResponseEntity<VendorReportResponse> getVendorReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate snapshotDate,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(vendorAndStockService.getVendorReport(
                from, to, snapshotDate, vendorId, categoryId, page, size
        ));
    }

    @GetMapping("/stock-ageing")
    public ResponseEntity<StockAgeingReportResponse> getStockAgeingReport(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID skuId,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(vendorAndStockService.getStockAgeingReport(
                categoryId, skuId, vendorId, page, size
        ));
    }

    @GetMapping("/collections")
    public ResponseEntity<CollectionsReportResponse> getCollectionsReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(salesAndCollectionsService.getCollectionsReport(
                from, to, customerId, page, size
        ));
    }

    @GetMapping("/stock-audits")
    public ResponseEntity<StockAuditReportResponse> getStockAuditReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID skuId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(vendorAndStockService.getStockAuditReport(
                from, to, categoryId, skuId, page, size
        ));
    }
}
