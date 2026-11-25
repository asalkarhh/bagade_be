package com.bagade.billing.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "bill")
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bill_number", nullable = false, unique = true, length = 50)
    private String billNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "issued_by", nullable = false, updatable = false)
    private UUID issuedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BillStatus status;

    @Column(name = "current_revision_number", nullable = false)
    private int currentRevisionNumber;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountTotal;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal paid;

    @Column(name = "balance_due", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceDue;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Bill() {
    }

    public Bill(
            String billNumber,
            Customer customer,
            LocalDate issueDate,
            Instant issuedAt,
            UUID issuedBy,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal total,
            BigDecimal paid,
            BigDecimal balanceDue) {
        this.billNumber = billNumber;
        this.customer = customer;
        this.issueDate = issueDate;
        this.issuedAt = issuedAt;
        this.issuedBy = issuedBy;
        this.status = BillStatus.ISSUED;
        this.currentRevisionNumber = 1;
        this.subtotal = subtotal;
        this.discountTotal = discountTotal;
        this.total = total;
        this.paid = paid;
        this.balanceDue = balanceDue;
        this.updatedAt = issuedAt;
    }

    public void applyFinancialUpdate(
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal total,
            BigDecimal paid,
            BigDecimal balanceDue,
            Instant updatedAt) {
        this.subtotal = subtotal;
        this.discountTotal = discountTotal;
        this.total = total;
        this.paid = paid;
        this.balanceDue = balanceDue;
        this.updatedAt = updatedAt;
    }

    public void setStatus(BillStatus status) {
        this.status = status;
    }

    public void setCurrentRevisionNumber(int currentRevisionNumber) {
        this.currentRevisionNumber = currentRevisionNumber;
    }

    public void touchUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getBillNumber() {
        return billNumber;
    }

    public Customer getCustomer() {
        return customer;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public UUID getIssuedBy() {
        return issuedBy;
    }

    public BillStatus getStatus() {
        return status;
    }

    public int getCurrentRevisionNumber() {
        return currentRevisionNumber;
    }

    public Long getVersion() {
        return version;
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

    public BigDecimal getPaid() {
        return paid;
    }

    public BigDecimal getBalanceDue() {
        return balanceDue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bill that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
