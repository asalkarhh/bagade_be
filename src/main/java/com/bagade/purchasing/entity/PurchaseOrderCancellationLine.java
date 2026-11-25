package com.bagade.purchasing.entity;

import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "purchase_order_cancellation_line")
public class PurchaseOrderCancellationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cancellation_id", nullable = false)
    private PurchaseOrderCancellation cancellation;

    @Column(name = "purchase_order_line_id", nullable = false)
    private UUID purchaseOrderLineId;

    @Column(name = "cancelled_quantity", nullable = false)
    private int cancelledQuantity;

    public PurchaseOrderCancellationLine() {
    }

    public PurchaseOrderCancellationLine(
            PurchaseOrderCancellation cancellation,
            UUID purchaseOrderLineId,
            int cancelledQuantity) {
        this.cancellation = cancellation;
        this.purchaseOrderLineId = purchaseOrderLineId;
        this.cancelledQuantity = cancelledQuantity;
    }

    public UUID getId() {
        return id;
    }

    public PurchaseOrderCancellation getCancellation() {
        return cancellation;
    }

    public UUID getPurchaseOrderLineId() {
        return purchaseOrderLineId;
    }

    public int getCancelledQuantity() {
        return cancelledQuantity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PurchaseOrderCancellationLine that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
