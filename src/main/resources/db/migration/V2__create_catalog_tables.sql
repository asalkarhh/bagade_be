CREATE TABLE category (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    parent_id UUID REFERENCES category(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_category_parent_id ON category(parent_id);

CREATE TABLE product (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID NOT NULL REFERENCES category(id),
    name VARCHAR(200) NOT NULL,
    brand VARCHAR(150),
    design VARCHAR(150),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_product_category_id ON product(category_id);

CREATE TABLE sku (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES product(id),
    sku_code VARCHAR(100) NOT NULL,
    size VARCHAR(50),
    default_selling_price NUMERIC(12, 2) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_sku_price_non_negative CHECK (default_selling_price >= 0)
);

CREATE UNIQUE INDEX uk_sku_code ON sku (LOWER(sku_code));
CREATE UNIQUE INDEX uk_sku_product_size_not_null ON sku (product_id, LOWER(size)) WHERE size IS NOT NULL;
CREATE UNIQUE INDEX uk_sku_product_unsized ON sku (product_id) WHERE size IS NULL;
CREATE INDEX idx_sku_product_id ON sku(product_id);
CREATE INDEX idx_sku_active ON sku(active);
