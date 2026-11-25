package com.bagade.purchasing.entity;

import com.bagade.catalog.entity.Sku;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "purchase_order_line")
public class PurchaseOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku_id", nullable = false)
    private Sku sku;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "ordered_quantity", nullable = false)
    private int orderedQuantity;

    @Column(name = "received_quantity", nullable = false)
    private int receivedQuantity;

    @Column(name = "cancelled_quantity", nullable = false)
    private int cancelledQuantity;

    @Column(name = "agreed_unit_cost", precision = 12, scale = 2)
    private BigDecimal agreedUnitCost;

    public PurchaseOrderLine() {
    }

    public PurchaseOrderLine(Sku sku, int lineNumber, int orderedQuantity, BigDecimal agreedUnitCost) {
        this.sku = sku;
        this.lineNumber = lineNumber;
        this.orderedQuantity = orderedQuantity;
        this.receivedQuantity = 0;
        this.cancelledQuantity = 0;
        this.agreedUnitCost = agreedUnitCost;
    }

    public int getOpenQuantity() {
        return orderedQuantity - receivedQuantity - cancelledQuantity;
    }

    public int cancelOpenQuantity() {
        int open = getOpenQuantity();
        if (open > 0) {
            this.cancelledQuantity += open;
        }
        return open;
    }

    /**
     * Domain hook for stock receipt integration (BE04) to record received units transactionally.
     */
    public void recordReceipt(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Receipt quantity must be greater than 0");
        }
        if (quantity > getOpenQuantity()) {
            throw new IllegalStateException("Receipt quantity exceeds open quantity");
        }
        this.receivedQuantity += quantity;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public PurchaseOrder getPurchaseOrder() {
        return purchaseOrder;
    }

    public void setPurchaseOrder(PurchaseOrder purchaseOrder) {
        this.purchaseOrder = purchaseOrder;
    }

    public Sku getSku() {
        return sku;
    }

    public void setSku(Sku sku) {
        this.sku = sku;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public int getOrderedQuantity() {
        return orderedQuantity;
    }

    public void setOrderedQuantity(int orderedQuantity) {
        this.orderedQuantity = orderedQuantity;
    }

    public int getReceivedQuantity() {
        return receivedQuantity;
    }

    public void setReceivedQuantity(int receivedQuantity) {
        this.receivedQuantity = receivedQuantity;
    }

    public int getCancelledQuantity() {
        return cancelledQuantity;
    }

    public void setCancelledQuantity(int cancelledQuantity) {
        this.cancelledQuantity = cancelledQuantity;
    }

    public BigDecimal getAgreedUnitCost() {
        return agreedUnitCost;
    }

    public void setAgreedUnitCost(BigDecimal agreedUnitCost) {
        this.agreedUnitCost = agreedUnitCost;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PurchaseOrderLine that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
