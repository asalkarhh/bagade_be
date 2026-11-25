package com.bagade.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String code,
        String message,
        String requestId,
        List<FieldErrorItem> fieldErrors,
        List<SelectableBatchDetail> selectableBatches,
        BillTotalsSnapshot currentTotals,
        List<ChangedStockSkuDetail> changedSkus
) {
    public ApiErrorResponse(String code, String message, String requestId) {
        this(code, message, requestId, null, null, null, null);
    }

    public ApiErrorResponse(String code, String message, String requestId, List<FieldErrorItem> fieldErrors) {
        this(code, message, requestId, fieldErrors, null, null, null);
    }

    public ApiErrorResponse(
            String code,
            String message,
            String requestId,
            List<FieldErrorItem> fieldErrors,
            List<SelectableBatchDetail> selectableBatches,
            BillTotalsSnapshot currentTotals) {
        this(code, message, requestId, fieldErrors, selectableBatches, currentTotals, null);
    }
}
