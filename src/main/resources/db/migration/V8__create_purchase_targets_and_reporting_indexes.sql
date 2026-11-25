CREATE TABLE purchase_target (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku_id UUID NOT NULL REFERENCES sku(id),
    planning_period VARCHAR(50),
    target_quantity INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES user_account(id),
    CONSTRAINT chk_purchase_target_qty_non_negative CHECK (target_quantity >= 0)
);

CREATE UNIQUE INDEX uk_purchase_target_sku_period_not_null
    ON purchase_target (sku_id, LOWER(planning_period))
    WHERE planning_period IS NOT NULL;

CREATE UNIQUE INDEX uk_purchase_target_sku_default
    ON purchase_target (sku_id)
    WHERE planning_period IS NULL;

CREATE INDEX idx_purchase_target_sku_id ON purchase_target(sku_id);

-- Support optional unknown receipt dates directly in schema when needed
ALTER TABLE stock_batch
    ALTER COLUMN received_date DROP NOT NULL;

-- Practical indexes for BE08 owner reporting and purchase planning
CREATE INDEX idx_billing_event_occurred_at ON billing_event(occurred_at);
CREATE INDEX idx_payment_entry_posted_at ON payment_entry(posted_at);
CREATE INDEX idx_sale_alloc_reversal_reversed_at ON sale_allocation_reversal(reversed_at);
CREATE INDEX idx_stock_count_status_posted_at ON stock_count(status, posted_at DESC);
