CREATE TABLE customer (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    contact VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL REFERENCES user_account(id)
);

CREATE INDEX idx_customer_name ON customer(name);
CREATE INDEX idx_customer_contact ON customer(contact);

CREATE SEQUENCE bill_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE bill (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bill_number VARCHAR(50) NOT NULL,
    customer_id UUID REFERENCES customer(id),
    issue_date DATE NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    issued_by UUID NOT NULL REFERENCES user_account(id),
    status VARCHAR(30) NOT NULL,
    current_revision_number INTEGER NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    subtotal NUMERIC(12, 2) NOT NULL,
    discount_total NUMERIC(12, 2) NOT NULL,
    total NUMERIC(12, 2) NOT NULL,
    paid NUMERIC(12, 2) NOT NULL,
    balance_due NUMERIC(12, 2) NOT NULL,
    CONSTRAINT uk_bill_number UNIQUE (bill_number),
    CONSTRAINT chk_bill_status CHECK (status IN ('ISSUED', 'PARTIALLY_RETURNED', 'RETURNED', 'CANCELLED')),
    CONSTRAINT chk_bill_revision_positive CHECK (current_revision_number >= 1),
    CONSTRAINT chk_bill_subtotal_non_negative CHECK (subtotal >= 0),
    CONSTRAINT chk_bill_discount_non_negative CHECK (discount_total >= 0),
    CONSTRAINT chk_bill_total_non_negative CHECK (total >= 0),
    CONSTRAINT chk_bill_paid_non_negative CHECK (paid >= 0),
    CONSTRAINT chk_bill_balance_due_non_negative CHECK (balance_due >= 0),
    CONSTRAINT chk_bill_paid_not_above_total CHECK (paid <= total),
    CONSTRAINT chk_bill_credit_requires_customer CHECK (balance_due = 0 OR customer_id IS NOT NULL)
);

CREATE INDEX idx_bill_issue_date ON bill(issue_date DESC, issued_at DESC);
CREATE INDEX idx_bill_customer_id ON bill(customer_id);

CREATE TABLE bill_revision (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bill_id UUID NOT NULL REFERENCES bill(id),
    revision_number INTEGER NOT NULL,
    subtotal NUMERIC(12, 2) NOT NULL,
    discount_total NUMERIC(12, 2) NOT NULL,
    total NUMERIC(12, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL REFERENCES user_account(id),
    CONSTRAINT uk_bill_revision_number UNIQUE (bill_id, revision_number),
    CONSTRAINT chk_bill_revision_num_positive CHECK (revision_number >= 1),
    CONSTRAINT chk_bill_rev_subtotal_non_negative CHECK (subtotal >= 0),
    CONSTRAINT chk_bill_rev_discount_non_negative CHECK (discount_total >= 0),
    CONSTRAINT chk_bill_rev_total_non_negative CHECK (total >= 0)
);

CREATE INDEX idx_bill_revision_bill_id ON bill_revision(bill_id);

CREATE TABLE bill_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bill_revision_id UUID NOT NULL REFERENCES bill_revision(id),
    logical_line_id UUID NOT NULL,
    line_number INTEGER NOT NULL DEFAULT 0,
    sku_id UUID NOT NULL REFERENCES sku(id),
    sku_code_snapshot VARCHAR(100) NOT NULL,
    product_name_snapshot VARCHAR(200) NOT NULL,
    size_snapshot VARCHAR(50),
    quantity INTEGER NOT NULL,
    returned_quantity INTEGER NOT NULL DEFAULT 0,
    unit_selling_price NUMERIC(12, 2) NOT NULL,
    discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    line_total NUMERIC(12, 2) NOT NULL,
    CONSTRAINT chk_bill_line_qty_positive CHECK (quantity > 0),
    CONSTRAINT chk_bill_line_returned_bounds CHECK (returned_quantity >= 0 AND returned_quantity <= quantity),
    CONSTRAINT chk_bill_line_unit_price_non_negative CHECK (unit_selling_price >= 0),
    CONSTRAINT chk_bill_line_discount_non_negative CHECK (discount_amount >= 0),
    CONSTRAINT chk_bill_line_total_non_negative CHECK (line_total >= 0)
);

CREATE INDEX idx_bill_line_revision_id ON bill_line(bill_revision_id);
CREATE INDEX idx_bill_line_logical_id ON bill_line(logical_line_id);
CREATE INDEX idx_bill_line_sku_id ON bill_line(sku_id);

CREATE TABLE billing_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bill_id UUID NOT NULL REFERENCES bill(id),
    bill_revision_id UUID NOT NULL REFERENCES bill_revision(id),
    event_type VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id UUID NOT NULL REFERENCES user_account(id),
    CONSTRAINT chk_billing_event_type CHECK (event_type IN ('ISSUE', 'RETURN', 'EXCHANGE', 'EDIT', 'CANCEL'))
);

CREATE INDEX idx_billing_event_bill_id ON billing_event(bill_id);

CREATE TABLE billing_event_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    billing_event_id UUID NOT NULL REFERENCES billing_event(id),
    logical_line_id UUID NOT NULL,
    sku_id UUID NOT NULL REFERENCES sku(id),
    quantity_delta INTEGER NOT NULL,
    line_total_delta NUMERIC(12, 2) NOT NULL
);

CREATE INDEX idx_billing_event_line_event_id ON billing_event_line(billing_event_id);

CREATE TABLE sale_allocation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bill_line_id UUID NOT NULL REFERENCES bill_line(id),
    batch_id UUID NOT NULL REFERENCES stock_batch(id),
    sku_id UUID NOT NULL REFERENCES sku(id),
    quantity INTEGER NOT NULL,
    captured_unit_cost NUMERIC(12, 2) NOT NULL,
    attribution_method VARCHAR(30) NOT NULL,
    CONSTRAINT chk_sale_allocation_qty_positive CHECK (quantity > 0),
    CONSTRAINT chk_sale_allocation_cost_non_negative CHECK (captured_unit_cost >= 0),
    CONSTRAINT chk_sale_allocation_method CHECK (attribution_method IN ('EXPLICIT_BATCH', 'FIFO'))
);

CREATE INDEX idx_sale_allocation_bill_line_id ON sale_allocation(bill_line_id);
CREATE INDEX idx_sale_allocation_batch_id ON sale_allocation(batch_id);

CREATE TABLE payment_entry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bill_id UUID NOT NULL REFERENCES bill(id),
    mode VARCHAR(20) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    reference VARCHAR(150),
    posted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    posted_by UUID NOT NULL REFERENCES user_account(id),
    CONSTRAINT chk_payment_entry_mode CHECK (mode IN ('CASH', 'UPI')),
    CONSTRAINT chk_payment_entry_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_payment_entry_bill_id ON payment_entry(bill_id);

ALTER TABLE stock_movement
    ADD COLUMN source_sale_allocation_id UUID REFERENCES sale_allocation(id);

CREATE INDEX idx_stock_movement_source_sale_allocation_id
    ON stock_movement(source_sale_allocation_id);
