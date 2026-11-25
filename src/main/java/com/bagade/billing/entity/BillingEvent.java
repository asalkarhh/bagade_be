package com.bagade.billing.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "billing_event")
public class BillingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_revision_id", nullable = false)
    private BillRevision billRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private BillingEventType eventType;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(length = 500)
    private String reason;

    @OneToMany(mappedBy = "billingEvent", cascade = CascadeType.ALL)
    private List<BillingEventLine> lines = new ArrayList<>();

    public BillingEvent() {
    }

    public BillingEvent(
            Bill bill,
            BillRevision billRevision,
            BillingEventType eventType,
            Instant occurredAt,
            UUID actorId) {
        this(bill, billRevision, eventType, occurredAt, actorId, null);
    }

    public BillingEvent(
            Bill bill,
            BillRevision billRevision,
            BillingEventType eventType,
            Instant occurredAt,
            UUID actorId,
            String reason) {
        this.bill = bill;
        this.billRevision = billRevision;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.actorId = actorId;
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    public void addLine(BillingEventLine line) {
        line.setBillingEvent(this);
        this.lines.add(line);
    }

    public UUID getId() {
        return id;
    }

    public Bill getBill() {
        return bill;
    }

    public BillRevision getBillRevision() {
        return billRevision;
    }

    public BillingEventType getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getActorId() {
        return actorId;
    }

    public List<BillingEventLine> getLines() {
        return lines;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BillingEvent that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
