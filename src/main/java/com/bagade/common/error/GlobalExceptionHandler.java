package com.bagade.common.error;

import com.bagade.auth.exception.InvalidCredentialsException;
import com.bagade.auth.security.RequestIdFilter;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Authentication failed: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiErrorResponse("INVALID_CREDENTIALS", "Invalid username or password", requestId));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Resource not found: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("RESOURCE_NOT_FOUND", ex.getMessage(), requestId));
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateSku(
            DuplicateSkuException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Duplicate SKU conflict: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("DUPLICATE_SKU", ex.getMessage(), requestId));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(
            IdempotencyConflictException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Idempotency conflict: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("IDEMPOTENCY_CONFLICT", ex.getMessage(), requestId));
    }

    @ExceptionHandler(PoLineMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handlePoLineMismatch(
            PoLineMismatchException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] PO line mismatch: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("PO_LINE_MISMATCH", ex.getMessage(), requestId));
    }

    @ExceptionHandler(PoQuantityExceededException.class)
    public ResponseEntity<ApiErrorResponse> handlePoQuantityExceeded(
            PoQuantityExceededException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] PO quantity exceeded: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("PO_QUANTITY_EXCEEDED", ex.getMessage(), requestId));
    }

    @ExceptionHandler(BarcodeConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleBarcodeConflict(
            BarcodeConflictException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Barcode conflict: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("BARCODE_CONFLICT", ex.getMessage(), requestId));
    }

    @ExceptionHandler(BarcodeNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleBarcodeNotFound(
            BarcodeNotFoundException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Barcode not found: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("BARCODE_NOT_FOUND", ex.getMessage(), requestId));
    }

    @ExceptionHandler(LabelNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleLabelNotFound(
            LabelNotFoundException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Label not found: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("LABEL_NOT_FOUND", ex.getMessage(), requestId));
    }

    @ExceptionHandler(BatchSelectionRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handleBatchSelectionRequired(
            BatchSelectionRequiredException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Batch selection required: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        "BATCH_SELECTION_REQUIRED",
                        ex.getMessage(),
                        requestId,
                        null,
                        ex.getSelectableBatches(),
                        null
                ));
    }

    @ExceptionHandler(PriceChangedException.class)
    public ResponseEntity<ApiErrorResponse> handlePriceChanged(
            PriceChangedException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Price changed: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        "PRICE_CHANGED",
                        ex.getMessage(),
                        requestId,
                        null,
                        null,
                        ex.getCurrentTotals()
                ));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientStock(
            InsufficientStockException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Insufficient stock: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("INSUFFICIENT_STOCK", ex.getMessage(), requestId));
    }

    @ExceptionHandler(ReturnQuantityExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleReturnQuantityExceeded(
            ReturnQuantityExceededException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Return quantity exceeded: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("RETURN_QUANTITY_EXCEEDED", ex.getMessage(), requestId));
    }

    @ExceptionHandler(BillCancelledException.class)
    public ResponseEntity<ApiErrorResponse> handleBillCancelled(
            BillCancelledException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Bill already cancelled: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("BILL_CANCELLED", ex.getMessage(), requestId));
    }

    @ExceptionHandler(PaymentExceedsBalanceException.class)
    public ResponseEntity<ApiErrorResponse> handlePaymentExceedsBalance(
            PaymentExceedsBalanceException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Payment exceeds balance due: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("PAYMENT_EXCEEDS_BALANCE", ex.getMessage(), requestId));
    }

    @ExceptionHandler({
            StaleVersionException.class,
            ObjectOptimisticLockingFailureException.class,
            OptimisticLockException.class
    })
    public ResponseEntity<ApiErrorResponse> handleStaleVersion(
            Exception ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Optimistic lock conflict: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("STALE_VERSION", "Resource was modified by another request", requestId));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        if (isSkuUniqueViolation(ex)) {
            log.warn("[{}] Database SKU unique conflict: {}", requestId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiErrorResponse(
                            "DUPLICATE_SKU",
                            "A SKU with this code or product-and-size combination already exists",
                            requestId));
        }
        if (isIdempotencyViolation(ex)) {
            log.warn("[{}] Database idempotency conflict: {}", requestId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiErrorResponse(
                            "IDEMPOTENCY_CONFLICT",
                            "Idempotency-Key was already used",
                            requestId));
        }

        log.warn("[{}] Data integrity violation: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("DATA_INTEGRITY_ERROR", "Data integrity conflict", requestId));
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleRequestValidation(
            RequestValidationException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Request validation error: {}", requestId, ex.getFieldErrors());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("VALIDATION_ERROR", ex.getMessage(), requestId, ex.getFieldErrors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        List<FieldErrorItem> fieldErrors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new FieldErrorItem(fe.getField(), fe.getDefaultMessage()));
        }
        for (ObjectError oe : ex.getBindingResult().getGlobalErrors()) {
            fieldErrors.add(new FieldErrorItem(oe.getObjectName(), oe.getDefaultMessage()));
        }

        log.warn("[{}] Validation error: {}", requestId, fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("VALIDATION_ERROR", "Validation failed", requestId, fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        List<FieldErrorItem> fieldErrors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            int lastDot = path.lastIndexOf('.');
            String field = lastDot >= 0 ? path.substring(lastDot + 1) : path;
            fieldErrors.add(new FieldErrorItem(field, violation.getMessage()));
        }

        log.warn("[{}] Constraint validation error: {}", requestId, fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("VALIDATION_ERROR", "Validation failed", requestId, fieldErrors));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        String field = ex.getName();
        List<FieldErrorItem> fieldErrors = List.of(
                new FieldErrorItem(field, "Invalid value for " + field)
        );
        log.warn("[{}] Type mismatch for parameter '{}': {}", requestId, field, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("VALIDATION_ERROR", "Validation failed", requestId, fieldErrors));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(
            MissingRequestHeaderException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        String headerName = ex.getHeaderName();
        List<FieldErrorItem> fieldErrors = List.of(
                new FieldErrorItem(headerName, headerName + " header is required")
        );
        log.warn("[{}] Missing required header '{}': {}", requestId, headerName, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("VALIDATION_ERROR", "Validation failed", requestId, fieldErrors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        String paramName = ex.getParameterName();
        List<FieldErrorItem> fieldErrors = List.of(
                new FieldErrorItem(paramName, paramName + " parameter is required")
        );
        log.warn("[{}] Missing required parameter '{}': {}", requestId, paramName, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("VALIDATION_ERROR", "Validation failed", requestId, fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Unreadable request body: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("VALIDATION_ERROR", "Malformed or invalid request body", requestId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Access denied: {}", requestId, ex.getMessage());

        String code = (ex instanceof CsrfException) ? "CSRF_ERROR" : "ACCESS_DENIED";
        String message = (ex instanceof CsrfException)
                ? "Invalid or missing CSRF token"
                : "Access denied: insufficient permissions";

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorResponse(code, message, requestId));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationException(
            AuthenticationException ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.warn("[{}] Authentication required: {}", requestId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiErrorResponse("AUTH_REQUIRED", "Authentication required", requestId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneralException(
            Exception ex, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        log.error("[{}] Unexpected server error", requestId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("INTERNAL_SERVER_ERROR", "An unexpected error occurred", requestId));
    }

    public static boolean isSkuUniqueViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String constraintName = cve.getConstraintName();
                if (constraintName != null && isSkuConstraintName(constraintName)) {
                    return true;
                }
            }
            if (current instanceof SQLException sqlEx && "23505".equals(sqlEx.getSQLState())) {
                String msg = sqlEx.getMessage() != null ? sqlEx.getMessage().toLowerCase(Locale.ROOT) : "";
                if (msg.contains("uk_sku_") || msg.contains("sku")) {
                    return true;
                }
            }
            String msg = current.getMessage() != null ? current.getMessage().toLowerCase(Locale.ROOT) : "";
            if (msg.contains("uk_sku_code")
                    || msg.contains("uk_sku_product_size_not_null")
                    || msg.contains("uk_sku_product_unsized")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isSkuConstraintName(String constraintName) {
        String lower = constraintName.toLowerCase(Locale.ROOT);
        return lower.contains("uk_sku_code")
                || lower.contains("uk_sku_product_size_not_null")
                || lower.contains("uk_sku_product_unsized");
    }

    public static boolean isIdempotencyViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String constraintName = cve.getConstraintName();
                if (constraintName != null && constraintName.toLowerCase(Locale.ROOT).contains("uk_idempotency_operation_key")) {
                    return true;
                }
            }
            String msg = current.getMessage() != null ? current.getMessage().toLowerCase(Locale.ROOT) : "";
            if (msg.contains("uk_idempotency_operation_key")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
