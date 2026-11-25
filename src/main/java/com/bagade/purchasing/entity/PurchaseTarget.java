package com.bagade.purchasing.entity;

import com.bagade.catalog.entity.Sku;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "purchase_target")
public class PurchaseTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku_id", nullable = false)
    private Sku sku;

    @Column(name = "planning_period", length = 50)
    private String planningPeriod;

    @Column(name = "target_quantity", nullable = false)
    private int targetQuantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    public PurchaseTarget() {
    }

    public UUID getId() {
        return id;
    }

    public Sku getSku() {
        return sku;
    }

    public String getPlanningPeriod() {
        return planningPeriod;
    }

    public int getTargetQuantity() {
        return targetQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PurchaseTarget that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
