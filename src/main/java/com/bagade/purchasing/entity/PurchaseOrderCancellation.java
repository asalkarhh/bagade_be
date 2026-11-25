package com.bagade.purchasing.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "purchase_order_cancellation")
public class PurchaseOrderCancellation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "cancelled_by", nullable = false)
    private UUID cancelledBy;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "cancelled_at", nullable = false, updatable = false)
    private Instant cancelledAt = Instant.now();

    @OneToMany(mappedBy = "cancellation", cascade = CascadeType.ALL)
    private List<PurchaseOrderCancellationLine> affectedLines = new ArrayList<>();

    public PurchaseOrderCancellation() {
    }

    public PurchaseOrderCancellation(UUID purchaseOrderId, UUID cancelledBy, String reason) {
        this.purchaseOrderId = purchaseOrderId;
        this.cancelledBy = cancelledBy;
        this.reason = reason;
        this.cancelledAt = Instant.now();
    }

    public void addAffectedLine(UUID purchaseOrderLineId, int cancelledQuantity) {
        PurchaseOrderCancellationLine line = new PurchaseOrderCancellationLine(
                this,
                purchaseOrderLineId,
                cancelledQuantity
        );
        this.affectedLines.add(line);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public UUID getCancelledBy() {
        return cancelledBy;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<PurchaseOrderCancellationLine> getAffectedLines() {
        return affectedLines;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PurchaseOrderCancellation that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
