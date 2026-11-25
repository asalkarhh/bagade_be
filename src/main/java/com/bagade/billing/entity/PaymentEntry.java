package com.bagade.billing.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payment_entry")
public class PaymentEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMode mode;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 150)
    private String reference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "billing_event_id")
    private BillingEvent billingEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_revision_id")
    private BillRevision billRevision;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @Column(name = "posted_by", nullable = false, updatable = false)
    private UUID postedBy;

    public PaymentEntry() {
    }

    public PaymentEntry(
            Bill bill,
            PaymentMode mode,
            BigDecimal amount,
            String reference,
            Instant postedAt,
            UUID postedBy) {
        this(bill, PaymentDirection.PAYMENT, mode, amount, reference, null, null, postedAt, postedBy);
    }

    public PaymentEntry(
            Bill bill,
            PaymentDirection direction,
            PaymentMode mode,
            BigDecimal amount,
            String reference,
            BillingEvent billingEvent,
            BillRevision billRevision,
            Instant postedAt,
            UUID postedBy) {
        this.bill = bill;
        this.direction = direction;
        this.mode = mode;
        this.amount = amount;
        this.reference = reference;
        this.billingEvent = billingEvent;
        this.billRevision = billRevision;
        this.postedAt = postedAt;
        this.postedBy = postedBy;
    }

    public PaymentDirection getDirection() {
        return direction;
    }

    public BillingEvent getBillingEvent() {
        return billingEvent;
    }

    public BillRevision getBillRevision() {
        return billRevision;
    }

    public UUID getId() {
        return id;
    }

    public Bill getBill() {
        return bill;
    }

    public PaymentMode getMode() {
        return mode;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReference() {
        return reference;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public UUID getPostedBy() {
        return postedBy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentEntry that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
