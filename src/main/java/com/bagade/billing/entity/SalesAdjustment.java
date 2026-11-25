package com.bagade.billing.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "sales_adjustment")
public class SalesAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "billing_event_id", nullable = false)
    private BillingEvent billingEvent;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 30)
    private BillingEventType adjustmentType;

    @Column(name = "subtotal_delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotalDelta;

    @Column(name = "discount_delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountDelta;

    @Column(name = "amount_delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountDelta;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "posted_by", nullable = false, updatable = false)
    private UUID postedBy;

    public SalesAdjustment() {
    }

    public SalesAdjustment(
            Bill bill,
            BillingEvent billingEvent,
            BillingEventType adjustmentType,
            BigDecimal subtotalDelta,
            BigDecimal discountDelta,
            BigDecimal amountDelta,
            Instant occurredAt,
            UUID postedBy) {
        this.bill = bill;
        this.billingEvent = billingEvent;
        this.adjustmentType = adjustmentType;
        this.subtotalDelta = subtotalDelta;
        this.discountDelta = discountDelta;
        this.amountDelta = amountDelta;
        this.occurredAt = occurredAt;
        this.postedBy = postedBy;
    }

    public UUID getId() {
        return id;
    }

    public Bill getBill() {
        return bill;
    }

    public BillingEvent getBillingEvent() {
        return billingEvent;
    }

    public BillingEventType getAdjustmentType() {
        return adjustmentType;
    }

    public BigDecimal getSubtotalDelta() {
        return subtotalDelta;
    }

    public BigDecimal getDiscountDelta() {
        return discountDelta;
    }

    public BigDecimal getAmountDelta() {
        return amountDelta;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getPostedBy() {
        return postedBy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SalesAdjustment that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
