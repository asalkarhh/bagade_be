package com.bagade.inventory.entity;

import com.bagade.catalog.entity.Sku;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "stock_batch")
public class StockBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku_id", nullable = false)
    private Sku sku;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_receipt_line_id", unique = true)
    private StockReceiptLine stockReceiptLine;

    @Enumerated(EnumType.STRING)
    @Column(name = "batch_source", nullable = false, length = 30)
    private BatchSource batchSource = BatchSource.RECEIPT;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_stock_count_line_id")
    private StockCountLine sourceStockCountLine;

    @Column(name = "received_date", nullable = false)
    private LocalDate receivedDate;

    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "printed_mrp", precision = 12, scale = 2)
    private BigDecimal printedMrp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public StockBatch() {
    }

    public StockBatch(
            Sku sku,
            StockReceiptLine stockReceiptLine,
            LocalDate receivedDate,
            BigDecimal unitCost,
            BigDecimal printedMrp) {
        this.sku = sku;
        this.stockReceiptLine = stockReceiptLine;
        this.batchSource = BatchSource.RECEIPT;
        this.receivedDate = receivedDate;
        this.unitCost = unitCost;
        this.printedMrp = printedMrp;
        this.createdAt = Instant.now();
    }

    public static StockBatch auditSurplus(
            Sku sku,
            StockCountLine sourceStockCountLine,
            LocalDate receivedDate) {
        StockBatch batch = new StockBatch();
        batch.sku = sku;
        batch.stockReceiptLine = null;
        batch.batchSource = BatchSource.AUDIT_SURPLUS;
        batch.sourceStockCountLine = sourceStockCountLine;
        batch.receivedDate = receivedDate;
        batch.unitCost = null;
        batch.printedMrp = null;
        batch.createdAt = Instant.now();
        return batch;
    }

    public BatchSource getBatchSource() {
        return batchSource;
    }

    public StockCountLine getSourceStockCountLine() {
        return sourceStockCountLine;
    }

    public UUID getId() {
        return id;
    }

    public Sku getSku() {
        return sku;
    }

    public StockReceiptLine getStockReceiptLine() {
        return stockReceiptLine;
    }

    public LocalDate getReceivedDate() {
        return receivedDate;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public BigDecimal getPrintedMrp() {
        return printedMrp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StockBatch that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
