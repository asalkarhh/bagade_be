CREATE TABLE vendor (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    contact VARCHAR(200),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL REFERENCES user_account(id)
);

CREATE INDEX idx_vendor_active ON vendor(active);
CREATE INDEX idx_vendor_name ON vendor(name);

CREATE SEQUENCE purchase_order_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE purchase_order (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    po_number VARCHAR(50) NOT NULL,
    vendor_id UUID NOT NULL REFERENCES vendor(id),
    order_date DATE NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL REFERENCES user_account(id),
    CONSTRAINT uk_purchase_order_po_number UNIQUE (po_number),
    CONSTRAINT chk_purchase_order_status CHECK (status IN ('OPEN', 'PARTIALLY_RECEIVED', 'CLOSED', 'CANCELLED'))
);

CREATE INDEX idx_purchase_order_vendor_id ON purchase_order(vendor_id);
CREATE INDEX idx_purchase_order_order_date ON purchase_order(order_date DESC, created_at DESC);
CREATE INDEX idx_purchase_order_status ON purchase_order(status);

CREATE TABLE purchase_order_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_order_id UUID NOT NULL REFERENCES purchase_order(id),
    sku_id UUID NOT NULL REFERENCES sku(id),
    line_number INTEGER NOT NULL DEFAULT 0,
    ordered_quantity INTEGER NOT NULL,
    received_quantity INTEGER NOT NULL DEFAULT 0,
    cancelled_quantity INTEGER NOT NULL DEFAULT 0,
    agreed_unit_cost NUMERIC(12, 2),
    CONSTRAINT chk_po_line_ordered_positive CHECK (ordered_quantity > 0),
    CONSTRAINT chk_po_line_received_non_negative CHECK (received_quantity >= 0),
    CONSTRAINT chk_po_line_cancelled_non_negative CHECK (cancelled_quantity >= 0),
    CONSTRAINT chk_po_line_quantities_within_ordered CHECK (received_quantity + cancelled_quantity <= ordered_quantity),
    CONSTRAINT chk_po_line_agreed_cost_non_negative CHECK (agreed_unit_cost IS NULL OR agreed_unit_cost >= 0)
);

CREATE INDEX idx_po_line_purchase_order_id ON purchase_order_line(purchase_order_id);
CREATE INDEX idx_po_line_sku_id ON purchase_order_line(sku_id);

CREATE TABLE purchase_order_cancellation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_order_id UUID NOT NULL REFERENCES purchase_order(id),
    cancelled_by UUID NOT NULL REFERENCES user_account(id),
    reason VARCHAR(500) NOT NULL,
    cancelled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_po_cancellation_po_id ON purchase_order_cancellation(purchase_order_id);

CREATE TABLE purchase_order_cancellation_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cancellation_id UUID NOT NULL REFERENCES purchase_order_cancellation(id),
    purchase_order_line_id UUID NOT NULL REFERENCES purchase_order_line(id),
    cancelled_quantity INTEGER NOT NULL,
    CONSTRAINT chk_po_cancellation_line_qty_positive CHECK (cancelled_quantity > 0)
);

CREATE INDEX idx_po_cancellation_line_cancellation_id ON purchase_order_cancellation_line(cancellation_id);

CREATE TABLE idempotency_record (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operation VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status INTEGER NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL REFERENCES user_account(id),
    CONSTRAINT uk_idempotency_operation_key UNIQUE (operation, idempotency_key)
);
