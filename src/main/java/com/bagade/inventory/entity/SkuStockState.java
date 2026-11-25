package com.bagade.inventory.entity;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "sku_stock_state")
public class SkuStockState implements Persistable<UUID> {

    @Id
    @Column(name = "sku_id")
    private UUID skuId;

    @Column(name = "stock_version", nullable = false)
    private long stockVersion = 0L;

    @Transient
    private boolean isNew = false;

    public SkuStockState() {
    }

    public SkuStockState(UUID skuId) {
        this.skuId = skuId;
        this.stockVersion = 0L;
        this.isNew = true;
    }

    public void incrementStockVersion() {
        this.stockVersion += 1L;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public long getStockVersion() {
        return stockVersion;
    }

    @Override
    public UUID getId() {
        return skuId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SkuStockState that)) return false;
        return skuId != null && Objects.equals(skuId, that.skuId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(skuId);
    }
}
