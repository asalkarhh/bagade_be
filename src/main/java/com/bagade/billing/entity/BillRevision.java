package com.bagade.billing.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "bill_revision")
public class BillRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    @Column(name = "revision_number", nullable = false)
    private int revisionNumber;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountTotal;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "paid_snapshot", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidSnapshot;

    @Column(name = "balance_due_snapshot", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceDueSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @OneToMany(mappedBy = "billRevision", cascade = CascadeType.ALL)
    @OrderBy("lineNumber ASC, id ASC")
    private List<BillLine> lines = new ArrayList<>();

    public BillRevision() {
    }

    public BillRevision(
            Bill bill,
            int revisionNumber,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal total,
            Instant createdAt,
            UUID createdBy) {
        this(bill, revisionNumber, subtotal, discountTotal, total,
                BigDecimal.ZERO.setScale(2), total, createdAt, createdBy);
    }

    public BillRevision(
            Bill bill,
            int revisionNumber,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal total,
            BigDecimal paidSnapshot,
            BigDecimal balanceDueSnapshot,
            Instant createdAt,
            UUID createdBy) {
        this.bill = bill;
        this.revisionNumber = revisionNumber;
        this.subtotal = subtotal;
        this.discountTotal = discountTotal;
        this.total = total;
        this.paidSnapshot = paidSnapshot;
        this.balanceDueSnapshot = balanceDueSnapshot;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public void updatePaymentSnapshot(BigDecimal paidSnapshot, BigDecimal balanceDueSnapshot) {
        this.paidSnapshot = paidSnapshot;
        this.balanceDueSnapshot = balanceDueSnapshot;
    }

    public BigDecimal getPaidSnapshot() {
        return paidSnapshot;
    }

    public BigDecimal getBalanceDueSnapshot() {
        return balanceDueSnapshot;
    }

    public void addLine(BillLine line) {
        line.setBillRevision(this);
        this.lines.add(line);
    }

    public UUID getId() {
        return id;
    }

    public Bill getBill() {
        return bill;
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public List<BillLine> getLines() {
        return lines;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BillRevision that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
