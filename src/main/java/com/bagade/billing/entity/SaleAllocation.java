package com.bagade.billing.entity;

import com.bagade.catalog.entity.Sku;
import com.bagade.inventory.entity.StockBatch;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "sale_allocation")
public class SaleAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_line_id", nullable = false)
    private BillLine billLine;

    @Column(name = "bill_id", nullable = false)
    private UUID billId;

    @Column(name = "logical_line_id", nullable = false)
    private UUID logicalLineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private StockBatch batch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku_id", nullable = false)
    private Sku sku;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "reversed_quantity", nullable = false)
    private int reversedQuantity;

    @Column(name = "captured_unit_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal capturedUnitCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "attribution_method", nullable = false, length = 30)
    private AllocationAttributionMethod attributionMethod;

    public SaleAllocation() {
    }

    public SaleAllocation(
            BillLine billLine,
            StockBatch batch,
            Sku sku,
            int quantity,
            BigDecimal capturedUnitCost,
            AllocationAttributionMethod attributionMethod) {
        this.billLine = billLine;
        this.billId = billLine.getBillRevision().getBill().getId();
        this.logicalLineId = billLine.getLogicalLineId();
        this.batch = batch;
        this.sku = sku;
        this.quantity = quantity;
        this.reversedQuantity = 0;
        this.capturedUnitCost = capturedUnitCost;
        this.attributionMethod = attributionMethod;
    }

    public int getRemainingQuantity() {
        return quantity - reversedQuantity;
    }

    public void recordReversal(int qty) {
        if (qty <= 0 || this.reversedQuantity + qty > this.quantity) {
            throw new IllegalArgumentException("Reversal quantity out of bounds");
        }
        this.reversedQuantity += qty;
    }

    public UUID getBillId() {
        return billId;
    }

    public UUID getLogicalLineId() {
        return logicalLineId;
    }

    public int getReversedQuantity() {
        return reversedQuantity;
    }

    public UUID getId() {
        return id;
    }

    public BillLine getBillLine() {
        return billLine;
    }

    public StockBatch getBatch() {
        return batch;
    }

    public Sku getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getCapturedUnitCost() {
        return capturedUnitCost;
    }

    public AllocationAttributionMethod getAttributionMethod() {
        return attributionMethod;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SaleAllocation that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
