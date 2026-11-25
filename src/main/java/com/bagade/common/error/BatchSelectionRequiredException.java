package com.bagade.common.error;

import java.util.List;

public class BatchSelectionRequiredException extends RuntimeException {

    private final List<SelectableBatchDetail> selectableBatches;

    public BatchSelectionRequiredException(String message, List<SelectableBatchDetail> selectableBatches) {
        super(message);
        this.selectableBatches = selectableBatches;
    }

    public List<SelectableBatchDetail> getSelectableBatches() {
        return selectableBatches;
    }
}
