package com.bagade.billing.controller;

import com.bagade.auth.security.AppUserPrincipal;
import com.bagade.billing.dto.BillDetailResponse;
import com.bagade.billing.dto.BillPreviewResponse;
import com.bagade.billing.dto.BillSummaryResponse;
import com.bagade.billing.dto.CancelBillRequest;
import com.bagade.billing.dto.CancelBillResponse;
import com.bagade.billing.dto.CollectBillPaymentRequest;
import com.bagade.billing.dto.CollectBillPaymentResponse;
import com.bagade.billing.dto.CreateBillRequest;
import com.bagade.billing.dto.CreateBillResponse;
import com.bagade.billing.dto.CreateBillRevisionRequest;
import com.bagade.billing.dto.EditBillResponse;
import com.bagade.billing.dto.ExchangeBillRequest;
import com.bagade.billing.dto.ExchangeBillResponse;
import com.bagade.billing.dto.PreviewBillRequest;
import com.bagade.billing.dto.ReturnBillRequest;
import com.bagade.billing.dto.ReturnBillResponse;
import com.bagade.billing.service.BillCorrectionService;
import com.bagade.billing.service.BillingService;
import com.bagade.common.dto.PagedResponse;
import com.bagade.common.error.RequestValidationException;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bills")
@PreAuthorize("hasAnyRole('OWNER', 'WORKER')")
public class BillController {

    private final BillingService billingService;
    private final BillCorrectionService billCorrectionService;

    public BillController(BillingService billingService, BillCorrectionService billCorrectionService) {
        this.billingService = billingService;
        this.billCorrectionService = billCorrectionService;
    }

    @PostMapping("/preview")
    public ResponseEntity<BillPreviewResponse> previewBill(@Valid @RequestBody PreviewBillRequest request) {
        return ResponseEntity.ok(billingService.previewBill(request));
    }

    @PostMapping
    public ResponseEntity<CreateBillResponse> createBill(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBillRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        CreateBillResponse created = billingService.createBill(request, idempotencyKey, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<PagedResponse<BillSummaryResponse>> listBills(
            @RequestParam(required = false) String billNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(billingService.listBills(billNumber, from, to, customerId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BillDetailResponse> getBill(@PathVariable UUID id) {
        return ResponseEntity.ok(billingService.getBillDetail(id));
    }

    @GetMapping("/{id}/print")
    public ResponseEntity<?> printBill(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer revision,
            @RequestParam(defaultValue = "json") String format) {
        String normalizedFormat = format == null ? "json" : format.trim().toLowerCase(Locale.ROOT);
        if ("json".equals(normalizedFormat)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(billingService.getPrintData(id, revision));
        }
        if ("pdf".equals(normalizedFormat)) {
            byte[] pdfBytes = billingService.renderPrintPdf(id, revision);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"bill-" + id + ".pdf\"")
                    .body(pdfBytes);
        }
        throw new RequestValidationException("format", "format must be json or pdf");
    }

    @PostMapping("/{id}/revisions")
    public ResponseEntity<EditBillResponse> reviseBill(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBillRevisionRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return ResponseEntity.ok(billCorrectionService.reviseBill(id, request, idempotencyKey, principal.getId()));
    }

    @PostMapping("/{id}/returns")
    public ResponseEntity<ReturnBillResponse> returnItems(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReturnBillRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        ReturnBillResponse response = billCorrectionService.returnItems(id, request, idempotencyKey, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/exchanges")
    public ResponseEntity<ExchangeBillResponse> exchangeItems(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ExchangeBillRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        ExchangeBillResponse response = billCorrectionService.exchangeItems(id, request, idempotencyKey, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/cancellations")
    public ResponseEntity<CancelBillResponse> cancelBill(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CancelBillRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return ResponseEntity.ok(billCorrectionService.cancelBill(id, request, idempotencyKey, principal.getId()));
    }

    @PostMapping("/{id}/payments")
    public ResponseEntity<CollectBillPaymentResponse> collectPayment(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CollectBillPaymentRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        CollectBillPaymentResponse response = billCorrectionService.collectPayment(id, request, idempotencyKey, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
