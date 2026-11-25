package com.bagade.purchasing.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "purchase_order")
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "po_number", nullable = false, unique = true, length = 50)
    private String poNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PurchaseOrderStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL)
    @OrderBy("lineNumber ASC, id ASC")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    public PurchaseOrder() {
    }

    public PurchaseOrder(String poNumber, Vendor vendor, LocalDate orderDate, UUID createdBy) {
        this.poNumber = poNumber;
        this.vendor = vendor;
        this.orderDate = orderDate;
        this.status = PurchaseOrderStatus.OPEN;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
        this.createdBy = createdBy;
    }

    public void touchUpdatedAt() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (this.updatedAt != null && !now.isAfter(this.updatedAt.truncatedTo(ChronoUnit.MICROS))) {
            this.updatedAt = this.updatedAt.truncatedTo(ChronoUnit.MICROS).plus(1, ChronoUnit.MICROS);
        } else {
            this.updatedAt = now;
        }
    }

    public void addLine(PurchaseOrderLine line) {
        line.setPurchaseOrder(this);
        this.lines.add(line);
    }

    public void recalculateStatus() {
        long ordered = 0;
        long received = 0;
        long cancelled = 0;
        for (PurchaseOrderLine line : lines) {
            ordered += line.getOrderedQuantity();
            received += line.getReceivedQuantity();
            cancelled += line.getCancelledQuantity();
        }
        this.status = deriveStatus(ordered, received, cancelled);
    }

    public static PurchaseOrderStatus deriveStatus(long totalOrdered, long totalReceived, long totalCancelled) {
        long totalOpen = totalOrdered - totalReceived - totalCancelled;
        if (totalOpen > 0) {
            return totalReceived > 0 ? PurchaseOrderStatus.PARTIALLY_RECEIVED : PurchaseOrderStatus.OPEN;
        }
        return totalReceived > 0 ? PurchaseOrderStatus.CLOSED : PurchaseOrderStatus.CANCELLED;
    }

    public long calculateTotalOpenQuantity() {
        long open = 0;
        for (PurchaseOrderLine line : lines) {
            open += line.getOpenQuantity();
        }
        return open;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getPoNumber() {
        return poNumber;
    }

    public void setPoNumber(String poNumber) {
        this.poNumber = poNumber;
    }

    public Vendor getVendor() {
        return vendor;
    }

    public void setVendor(Vendor vendor) {
        this.vendor = vendor;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(LocalDate orderDate) {
        this.orderDate = orderDate;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public void setStatus(PurchaseOrderStatus status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public List<PurchaseOrderLine> getLines() {
        return lines;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PurchaseOrder that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
