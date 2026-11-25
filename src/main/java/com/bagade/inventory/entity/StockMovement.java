package com.bagade.inventory.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "stock_movement")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private StockBatch batch;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_condition", nullable = false, length = 30)
    private StockCondition stockCondition;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 40)
    private MovementType movementType;

    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_receipt_line_id")
    private StockReceiptLine sourceReceiptLine;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @Column(name = "posted_by", nullable = false, updatable = false)
    private UUID postedBy;

    public StockMovement() {
    }

    public StockMovement(
            StockBatch batch,
            StockCondition stockCondition,
            MovementType movementType,
            int quantityDelta,
            StockReceiptLine sourceReceiptLine,
            Instant postedAt,
            UUID postedBy) {
        this.batch = batch;
        this.stockCondition = stockCondition;
        this.movementType = movementType;
        this.quantityDelta = quantityDelta;
        this.sourceReceiptLine = sourceReceiptLine;
        this.postedAt = postedAt;
        this.postedBy = postedBy;
    }

    public UUID getId() {
        return id;
    }

    public StockBatch getBatch() {
        return batch;
    }

    public StockCondition getStockCondition() {
        return stockCondition;
    }

    public MovementType getMovementType() {
        return movementType;
    }

    public int getQuantityDelta() {
        return quantityDelta;
    }

    public StockReceiptLine getSourceReceiptLine() {
        return sourceReceiptLine;
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
        if (!(o instanceof StockMovement that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
