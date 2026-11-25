ALTER TABLE bill
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE bill
SET updated_at = issued_at;

ALTER TABLE bill_revision
    ADD COLUMN paid_snapshot NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN balance_due_snapshot NUMERIC(12, 2) NOT NULL DEFAULT 0;

UPDATE bill_revision br
SET paid_snapshot = b.paid,
    balance_due_snapshot = b.balance_due
FROM bill b
WHERE b.id = br.bill_id;

ALTER TABLE bill_revision
    ADD CONSTRAINT chk_bill_rev_paid_non_negative CHECK (paid_snapshot >= 0),
    ADD CONSTRAINT chk_bill_rev_balance_due_non_negative CHECK (balance_due_snapshot >= 0);

ALTER TABLE bill_line
    ADD COLUMN initial_returned_quantity INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN returned_discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN returned_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_bill_line_initial_returned_bounds CHECK (initial_returned_quantity >= 0 AND initial_returned_quantity <= quantity),
    ADD CONSTRAINT chk_bill_line_returned_discount_bounds CHECK (returned_discount_amount >= 0 AND returned_discount_amount <= discount_amount),
    ADD CONSTRAINT chk_bill_line_returned_amount_bounds CHECK (returned_amount >= 0 AND returned_amount <= line_total);

ALTER TABLE billing_event
    ADD COLUMN reason VARCHAR(500);

ALTER TABLE payment_entry
    ADD COLUMN direction VARCHAR(20) NOT NULL DEFAULT 'PAYMENT',
    ADD COLUMN billing_event_id UUID REFERENCES billing_event(id),
    ADD COLUMN bill_revision_id UUID REFERENCES bill_revision(id),
    ADD CONSTRAINT chk_payment_entry_direction CHECK (direction IN ('PAYMENT', 'REFUND'));

UPDATE payment_entry pe
SET bill_revision_id = br.id
FROM bill_revision br
WHERE br.bill_id = pe.bill_id
  AND br.revision_number = 1;

CREATE INDEX idx_payment_entry_billing_event_id ON payment_entry(billing_event_id);
CREATE INDEX idx_payment_entry_bill_revision_id ON payment_entry(bill_revision_id);

CREATE TABLE sales_adjustment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bill_id UUID NOT NULL REFERENCES bill(id),
    billing_event_id UUID NOT NULL REFERENCES billing_event(id),
    adjustment_type VARCHAR(30) NOT NULL,
    subtotal_delta NUMERIC(12, 2) NOT NULL,
    discount_delta NUMERIC(12, 2) NOT NULL,
    amount_delta NUMERIC(12, 2) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    posted_by UUID NOT NULL REFERENCES user_account(id),
    CONSTRAINT chk_sales_adjustment_type CHECK (adjustment_type IN ('EDIT', 'RETURN', 'EXCHANGE', 'CANCEL'))
);

CREATE INDEX idx_sales_adjustment_bill_id ON sales_adjustment(bill_id);
CREATE INDEX idx_sales_adjustment_occurred_at ON sales_adjustment(occurred_at DESC);

ALTER TABLE sale_allocation
    ADD COLUMN bill_id UUID REFERENCES bill(id),
    ADD COLUMN logical_line_id UUID,
    ADD COLUMN reversed_quantity INTEGER NOT NULL DEFAULT 0;

UPDATE sale_allocation sa
SET bill_id = br.bill_id,
    logical_line_id = bl.logical_line_id
FROM bill_line bl
JOIN bill_revision br ON br.id = bl.bill_revision_id
WHERE bl.id = sa.bill_line_id;

ALTER TABLE sale_allocation
    ALTER COLUMN bill_id SET NOT NULL,
    ALTER COLUMN logical_line_id SET NOT NULL,
    ADD CONSTRAINT chk_sale_allocation_reversed_bounds CHECK (reversed_quantity >= 0 AND reversed_quantity <= quantity);

CREATE INDEX idx_sale_allocation_bill_logical ON sale_allocation(bill_id, logical_line_id);

CREATE TABLE sale_allocation_reversal (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sale_allocation_id UUID NOT NULL REFERENCES sale_allocation(id),
    billing_event_id UUID NOT NULL REFERENCES billing_event(id),
    batch_id UUID NOT NULL REFERENCES stock_batch(id),
    sku_id UUID NOT NULL REFERENCES sku(id),
    quantity INTEGER NOT NULL,
    captured_unit_cost NUMERIC(12, 2) NOT NULL,
    disposition VARCHAR(30) NOT NULL,
    reversed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reversed_by UUID NOT NULL REFERENCES user_account(id),
    CONSTRAINT chk_sale_alloc_reversal_qty_positive CHECK (quantity > 0),
    CONSTRAINT chk_sale_alloc_reversal_cost_non_negative CHECK (captured_unit_cost >= 0),
    CONSTRAINT chk_sale_alloc_reversal_disposition CHECK (disposition IN ('SELLABLE', 'NON_SELLABLE'))
);

CREATE INDEX idx_sale_alloc_reversal_alloc_id ON sale_allocation_reversal(sale_allocation_id);
CREATE INDEX idx_sale_alloc_reversal_event_id ON sale_allocation_reversal(billing_event_id);
CREATE INDEX idx_sale_alloc_reversal_batch_id ON sale_allocation_reversal(batch_id);

ALTER TABLE stock_movement
    ADD COLUMN source_sale_allocation_reversal_id UUID REFERENCES sale_allocation_reversal(id);

CREATE INDEX idx_stock_movement_source_reversal_id
    ON stock_movement(source_sale_allocation_reversal_id);
