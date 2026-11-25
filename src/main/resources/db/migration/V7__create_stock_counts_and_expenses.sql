CREATE TABLE stock_count (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope VARCHAR(20) NOT NULL,
    category_id UUID REFERENCES category(id),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL REFERENCES user_account(id),
    posted_at TIMESTAMP WITH TIME ZONE,
    posted_by UUID REFERENCES user_account(id),
    CONSTRAINT chk_stock_count_scope CHECK (scope IN ('SHOP', 'CATEGORY')),
    CONSTRAINT chk_stock_count_status CHECK (status IN ('DRAFT', 'POSTED')),
    CONSTRAINT chk_stock_count_scope_category CHECK (
        (scope = 'SHOP' AND category_id IS NULL)
        OR (scope = 'CATEGORY' AND category_id IS NOT NULL)
    )
);

CREATE INDEX idx_stock_count_status ON stock_count(status);
CREATE INDEX idx_stock_count_created_at ON stock_count(created_at DESC);

CREATE TABLE stock_count_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stock_count_id UUID NOT NULL REFERENCES stock_count(id),
    line_number INTEGER NOT NULL DEFAULT 0,
    sku_id UUID NOT NULL REFERENCES sku(id),
    counted_quantity INTEGER,
    expected_quantity INTEGER,
    observed_stock_version BIGINT,
    reason VARCHAR(500),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stock_count_line_count_sku UNIQUE (stock_count_id, sku_id),
    CONSTRAINT chk_stock_count_line_counted_non_negative CHECK (counted_quantity IS NULL OR counted_quantity >= 0),
    CONSTRAINT chk_stock_count_line_expected_non_negative CHECK (expected_quantity IS NULL OR expected_quantity >= 0),
    CONSTRAINT chk_stock_count_line_version_non_negative CHECK (observed_stock_version IS NULL OR observed_stock_version >= 0)
);

CREATE INDEX idx_stock_count_line_count_id ON stock_count_line(stock_count_id);
CREATE INDEX idx_stock_count_line_sku_id ON stock_count_line(sku_id);

-- Allow audit surplus batches without a vendor receipt or known unit cost
ALTER TABLE stock_batch
    ALTER COLUMN stock_receipt_line_id DROP NOT NULL,
    ALTER COLUMN unit_cost DROP NOT NULL,
    DROP CONSTRAINT chk_stock_batch_unit_cost_non_negative,
    ADD CONSTRAINT chk_stock_batch_unit_cost_non_negative CHECK (unit_cost IS NULL OR unit_cost >= 0),
    ADD COLUMN batch_source VARCHAR(30) NOT NULL DEFAULT 'RECEIPT',
    ADD COLUMN source_stock_count_line_id UUID REFERENCES stock_count_line(id),
    ADD CONSTRAINT chk_stock_batch_source CHECK (batch_source IN ('RECEIPT', 'AUDIT_SURPLUS')),
    ADD CONSTRAINT chk_stock_batch_source_links CHECK (
        (batch_source = 'RECEIPT' AND stock_receipt_line_id IS NOT NULL AND source_stock_count_line_id IS NULL)
        OR (batch_source = 'AUDIT_SURPLUS' AND stock_receipt_line_id IS NULL AND source_stock_count_line_id IS NOT NULL AND unit_cost IS NULL)
    );

CREATE UNIQUE INDEX uk_stock_batch_audit_count_line
    ON stock_batch(source_stock_count_line_id)
    WHERE source_stock_count_line_id IS NOT NULL;

-- Preserve null unit cost when billing/returning audit surplus stock with unknown cost
ALTER TABLE sale_allocation
    ALTER COLUMN captured_unit_cost DROP NOT NULL,
    DROP CONSTRAINT chk_sale_allocation_cost_non_negative,
    ADD CONSTRAINT chk_sale_allocation_cost_non_negative CHECK (captured_unit_cost IS NULL OR captured_unit_cost >= 0);

ALTER TABLE sale_allocation_reversal
    ALTER COLUMN captured_unit_cost DROP NOT NULL,
    DROP CONSTRAINT chk_sale_alloc_reversal_cost_non_negative,
    ADD CONSTRAINT chk_sale_alloc_reversal_cost_non_negative CHECK (captured_unit_cost IS NULL OR captured_unit_cost >= 0);

-- Link audit adjustment stock movements to stock_count_line and record FIFO shortage allocation basis
ALTER TABLE stock_movement
    ADD COLUMN source_stock_count_line_id UUID REFERENCES stock_count_line(id),
    ADD COLUMN allocation_basis VARCHAR(40),
    ADD CONSTRAINT chk_stock_movement_allocation_basis CHECK (
        allocation_basis IS NULL OR allocation_basis IN ('FIFO_SHORTAGE', 'AUDIT_SURPLUS')
    );

CREATE INDEX idx_stock_movement_source_count_line_id
    ON stock_movement(source_stock_count_line_id);

-- Operating expenses (separate from inventory purchases in stock receipts)
CREATE TABLE expense (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    expense_date DATE NOT NULL,
    category VARCHAR(100) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    payment_mode VARCHAR(20) NOT NULL,
    notes VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL REFERENCES user_account(id),
    CONSTRAINT chk_expense_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_expense_payment_mode CHECK (payment_mode IN ('CASH', 'UPI'))
);

CREATE INDEX idx_expense_date ON expense(expense_date DESC, created_at DESC, id DESC);
CREATE INDEX idx_expense_category ON expense(LOWER(category));
