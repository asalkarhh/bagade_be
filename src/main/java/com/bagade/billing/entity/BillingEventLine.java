package com.bagade.billing.entity;

import com.bagade.catalog.entity.Sku;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "billing_event_line")
public class BillingEventLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "billing_event_id", nullable = false)
    private BillingEvent billingEvent;

    @Column(name = "logical_line_id", nullable = false)
    private UUID logicalLineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku_id", nullable = false)
    private Sku sku;

    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;

    @Column(name = "line_total_delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotalDelta;

    public BillingEventLine() {
    }

    public BillingEventLine(UUID logicalLineId, Sku sku, int quantityDelta, BigDecimal lineTotalDelta) {
        this.logicalLineId = logicalLineId;
        this.sku = sku;
        this.quantityDelta = quantityDelta;
        this.lineTotalDelta = lineTotalDelta;
    }

    public UUID getId() {
        return id;
    }

    public BillingEvent getBillingEvent() {
        return billingEvent;
    }

    public void setBillingEvent(BillingEvent billingEvent) {
        this.billingEvent = billingEvent;
    }

    public UUID getLogicalLineId() {
        return logicalLineId;
    }

    public Sku getSku() {
        return sku;
    }

    public int getQuantityDelta() {
        return quantityDelta;
    }

    public BigDecimal getLineTotalDelta() {
        return lineTotalDelta;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BillingEventLine that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
