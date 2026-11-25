package com.bagade.inventory.entity;

import com.bagade.billing.entity.SaleAllocation;
import com.bagade.billing.entity.SaleAllocationReversal;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_sale_allocation_id")
    private SaleAllocation sourceSaleAllocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_sale_allocation_reversal_id")
    private SaleAllocationReversal sourceSaleAllocationReversal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_stock_count_line_id")
    private StockCountLine sourceStockCountLine;

    @Column(name = "allocation_basis", length = 40)
    private String allocationBasis;

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
        this(batch, stockCondition, movementType, quantityDelta, sourceReceiptLine, null, null, postedAt, postedBy);
    }

    public StockMovement(
            StockBatch batch,
            StockCondition stockCondition,
            MovementType movementType,
            int quantityDelta,
            StockReceiptLine sourceReceiptLine,
            SaleAllocation sourceSaleAllocation,
            Instant postedAt,
            UUID postedBy) {
        this(batch, stockCondition, movementType, quantityDelta, sourceReceiptLine, sourceSaleAllocation, null, postedAt, postedBy);
    }

    public StockMovement(
            StockBatch batch,
            StockCondition stockCondition,
            MovementType movementType,
            int quantityDelta,
            StockReceiptLine sourceReceiptLine,
            SaleAllocation sourceSaleAllocation,
            SaleAllocationReversal sourceSaleAllocationReversal,
            Instant postedAt,
            UUID postedBy) {
        this.batch = batch;
        this.stockCondition = stockCondition;
        this.movementType = movementType;
        this.quantityDelta = quantityDelta;
        this.sourceReceiptLine = sourceReceiptLine;
        this.sourceSaleAllocation = sourceSaleAllocation;
        this.sourceSaleAllocationReversal = sourceSaleAllocationReversal;
        this.postedAt = postedAt;
        this.postedBy = postedBy;
    }

    public static StockMovement forSale(
            StockBatch batch,
            int allocatedQuantity,
            SaleAllocation saleAllocation,
            Instant postedAt,
            UUID postedBy) {
        return new StockMovement(
                batch,
                StockCondition.SELLABLE,
                MovementType.SALE,
                -allocatedQuantity,
                null,
                saleAllocation,
                null,
                postedAt,
                postedBy
        );
    }

    public static StockMovement forSaleReversal(
            StockBatch batch,
            StockCondition stockCondition,
            MovementType movementType,
            int reversedQuantity,
            SaleAllocationReversal reversal,
            Instant postedAt,
            UUID postedBy) {
        return new StockMovement(
                batch,
                stockCondition,
                movementType,
                reversedQuantity,
                null,
                null,
                reversal,
                postedAt,
                postedBy
        );
    }

    public static StockMovement forStockCountAdjustment(
            StockBatch batch,
            int quantityDelta,
            StockCountLine sourceStockCountLine,
            String allocationBasis,
            Instant postedAt,
            UUID postedBy) {
        StockMovement movement = new StockMovement(
                batch,
                StockCondition.SELLABLE,
                MovementType.ADJUSTMENT,
                quantityDelta,
                null,
                null,
                null,
                postedAt,
                postedBy
        );
        movement.sourceStockCountLine = sourceStockCountLine;
        movement.allocationBasis = allocationBasis;
        return movement;
    }

    public StockCountLine getSourceStockCountLine() {
        return sourceStockCountLine;
    }

    public String getAllocationBasis() {
        return allocationBasis;
    }

    public SaleAllocationReversal getSourceSaleAllocationReversal() {
        return sourceSaleAllocationReversal;
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

    public SaleAllocation getSourceSaleAllocation() {
        return sourceSaleAllocation;
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
