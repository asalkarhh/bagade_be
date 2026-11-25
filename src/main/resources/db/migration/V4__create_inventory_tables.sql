CREATE SEQUENCE stock_receipt_number_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE internal_barcode_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE stock_receipt (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_number VARCHAR(50) NOT NULL,
    vendor_id UUID NOT NULL REFERENCES vendor(id),
    purchase_order_id UUID REFERENCES purchase_order(id),
    vendor_invoice_ref VARCHAR(150),
    received_date DATE NOT NULL,
    posted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    posted_by UUID NOT NULL REFERENCES user_account(id),
    CONSTRAINT uk_stock_receipt_number UNIQUE (receipt_number)
);

CREATE INDEX idx_stock_receipt_vendor_id ON stock_receipt(vendor_id);
CREATE INDEX idx_stock_receipt_po_id ON stock_receipt(purchase_order_id);
CREATE INDEX idx_stock_receipt_received_date ON stock_receipt(received_date DESC, posted_at DESC);

CREATE TABLE stock_receipt_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stock_receipt_id UUID NOT NULL REFERENCES stock_receipt(id),
    line_number INTEGER NOT NULL DEFAULT 0,
    sku_id UUID NOT NULL REFERENCES sku(id),
    purchase_order_line_id UUID REFERENCES purchase_order_line(id),
    quantity INTEGER NOT NULL,
    unit_cost NUMERIC(12, 2) NOT NULL,
    printed_mrp NUMERIC(12, 2),
    CONSTRAINT chk_stock_receipt_line_qty_positive CHECK (quantity > 0),
    CONSTRAINT chk_stock_receipt_line_unit_cost_non_negative CHECK (unit_cost >= 0),
    CONSTRAINT chk_stock_receipt_line_mrp_non_negative CHECK (printed_mrp IS NULL OR printed_mrp >= 0)
);

CREATE INDEX idx_stock_receipt_line_receipt_id ON stock_receipt_line(stock_receipt_id);
CREATE INDEX idx_stock_receipt_line_sku_id ON stock_receipt_line(sku_id);
CREATE INDEX idx_stock_receipt_line_po_line_id ON stock_receipt_line(purchase_order_line_id);

CREATE TABLE stock_batch (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku_id UUID NOT NULL REFERENCES sku(id),
    stock_receipt_line_id UUID NOT NULL REFERENCES stock_receipt_line(id),
    received_date DATE NOT NULL,
    unit_cost NUMERIC(12, 2) NOT NULL,
    printed_mrp NUMERIC(12, 2),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stock_batch_receipt_line UNIQUE (stock_receipt_line_id),
    CONSTRAINT chk_stock_batch_unit_cost_non_negative CHECK (unit_cost >= 0),
    CONSTRAINT chk_stock_batch_mrp_non_negative CHECK (printed_mrp IS NULL OR printed_mrp >= 0)
);

CREATE INDEX idx_stock_batch_sku_id ON stock_batch(sku_id);
CREATE INDEX idx_stock_batch_received_date ON stock_batch(received_date ASC, created_at ASC);

CREATE TABLE batch_balance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES stock_batch(id),
    stock_condition VARCHAR(30) NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT uk_batch_balance_batch_condition UNIQUE (batch_id, stock_condition),
    CONSTRAINT chk_batch_balance_condition CHECK (stock_condition IN ('SELLABLE', 'NON_SELLABLE')),
    CONSTRAINT chk_batch_balance_qty_non_negative CHECK (quantity >= 0)
);

CREATE INDEX idx_batch_balance_batch_id ON batch_balance(batch_id);

CREATE TABLE stock_movement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES stock_batch(id),
    stock_condition VARCHAR(30) NOT NULL,
    movement_type VARCHAR(40) NOT NULL,
    quantity_delta INTEGER NOT NULL,
    source_receipt_line_id UUID REFERENCES stock_receipt_line(id),
    posted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    posted_by UUID NOT NULL REFERENCES user_account(id),
    CONSTRAINT chk_stock_movement_condition CHECK (stock_condition IN ('SELLABLE', 'NON_SELLABLE')),
    CONSTRAINT chk_stock_movement_type CHECK (movement_type IN ('RECEIPT', 'SALE', 'RETURN', 'ADJUSTMENT')),
    CONSTRAINT chk_stock_movement_delta_non_zero CHECK (quantity_delta <> 0)
);

CREATE INDEX idx_stock_movement_batch_id ON stock_movement(batch_id);
CREATE INDEX idx_stock_movement_source_receipt_line_id ON stock_movement(source_receipt_line_id);

CREATE TABLE sku_stock_state (
    sku_id UUID PRIMARY KEY REFERENCES sku(id),
    stock_version BIGINT NOT NULL DEFAULT 0
);

INSERT INTO sku_stock_state (sku_id, stock_version)
SELECT id, 0 FROM sku
ON CONFLICT (sku_id) DO NOTHING;

CREATE TABLE barcode_mapping (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(150) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    sku_id UUID NOT NULL REFERENCES sku(id),
    batch_id UUID REFERENCES stock_batch(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID NOT NULL REFERENCES user_account(id),
    CONSTRAINT uk_barcode_mapping_code UNIQUE (code),
    CONSTRAINT chk_barcode_mapping_source_type CHECK (source_type IN ('BRAND', 'INTERNAL')),
    CONSTRAINT chk_barcode_mapping_batch_consistency CHECK (
        (source_type = 'BRAND' AND batch_id IS NULL)
        OR (source_type = 'INTERNAL' AND batch_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_barcode_mapping_internal_batch
    ON barcode_mapping (batch_id)
    WHERE source_type = 'INTERNAL';

CREATE INDEX idx_barcode_mapping_sku_id ON barcode_mapping(sku_id);
CREATE INDEX idx_barcode_mapping_batch_id ON barcode_mapping(batch_id);
