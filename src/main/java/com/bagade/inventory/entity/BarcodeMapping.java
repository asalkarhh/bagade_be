package com.bagade.inventory.entity;

import com.bagade.catalog.entity.Sku;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "barcode_mapping")
public class BarcodeMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 150)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private BarcodeSourceType sourceType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku_id", nullable = false)
    private Sku sku;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private StockBatch batch;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    public BarcodeMapping() {
    }

    public BarcodeMapping(
            String code,
            BarcodeSourceType sourceType,
            Sku sku,
            StockBatch batch,
            UUID createdBy) {
        this.code = code;
        this.sourceType = sourceType;
        this.sku = sku;
        this.batch = batch;
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public BarcodeSourceType getSourceType() {
        return sourceType;
    }

    public Sku getSku() {
        return sku;
    }

    public StockBatch getBatch() {
        return batch;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BarcodeMapping that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
