package com.bagade.billing.entity;

import com.bagade.catalog.entity.Sku;
import com.bagade.inventory.entity.StockBatch;
import com.bagade.inventory.entity.StockCondition;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "sale_allocation_reversal")
public class SaleAllocationReversal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_allocation_id", nullable = false)
    private SaleAllocation saleAllocation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "billing_event_id", nullable = false)
    private BillingEvent billingEvent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private StockBatch batch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku_id", nullable = false)
    private Sku sku;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "captured_unit_cost", precision = 12, scale = 2)
    private BigDecimal capturedUnitCost;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StockCondition disposition;

    @Column(name = "reversed_at", nullable = false, updatable = false)
    private Instant reversedAt;

    @Column(name = "reversed_by", nullable = false, updatable = false)
    private UUID reversedBy;

    public SaleAllocationReversal() {
    }

    public SaleAllocationReversal(
            SaleAllocation saleAllocation,
            BillingEvent billingEvent,
            StockBatch batch,
            Sku sku,
            int quantity,
            BigDecimal capturedUnitCost,
            StockCondition disposition,
            Instant reversedAt,
            UUID reversedBy) {
        this.saleAllocation = saleAllocation;
        this.billingEvent = billingEvent;
        this.batch = batch;
        this.sku = sku;
        this.quantity = quantity;
        this.capturedUnitCost = capturedUnitCost;
        this.disposition = disposition;
        this.reversedAt = reversedAt;
        this.reversedBy = reversedBy;
    }

    public UUID getId() {
        return id;
    }

    public SaleAllocation getSaleAllocation() {
        return saleAllocation;
    }

    public BillingEvent getBillingEvent() {
        return billingEvent;
    }

    public StockBatch getBatch() {
        return batch;
    }

    public Sku getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getCapturedUnitCost() {
        return capturedUnitCost;
    }

    public StockCondition getDisposition() {
        return disposition;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public UUID getReversedBy() {
        return reversedBy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SaleAllocationReversal that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
