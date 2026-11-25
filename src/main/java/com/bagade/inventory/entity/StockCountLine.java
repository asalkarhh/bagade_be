package com.bagade.inventory.entity;

import com.bagade.catalog.entity.Sku;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "stock_count_line")
public class StockCountLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_count_id", nullable = false)
    private StockCount stockCount;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku_id", nullable = false)
    private Sku sku;

    @Column(name = "counted_quantity")
    private Integer countedQuantity;

    @Column(name = "expected_quantity")
    private Integer expectedQuantity;

    @Column(name = "observed_stock_version")
    private Long observedStockVersion;

    @Column(length = 500)
    private String reason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public StockCountLine() {
    }

    public StockCountLine(int lineNumber, Sku sku, Instant createdAt) {
        this.lineNumber = lineNumber;
        this.sku = sku;
        this.updatedAt = createdAt;
    }

    public void updateCount(
            Integer countedQuantity,
            Integer expectedQuantity,
            Long observedStockVersion,
            String reason,
            Instant updatedAt) {
        this.countedQuantity = countedQuantity;
        this.expectedQuantity = expectedQuantity;
        this.observedStockVersion = observedStockVersion;
        this.reason = reason;
        this.updatedAt = updatedAt;
    }

    public Integer getDiscrepancy() {
        if (countedQuantity == null || expectedQuantity == null) {
            return null;
        }
        return countedQuantity - expectedQuantity;
    }

    public UUID getId() {
        return id;
    }

    public StockCount getStockCount() {
        return stockCount;
    }

    public void setStockCount(StockCount stockCount) {
        this.stockCount = stockCount;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public Sku getSku() {
        return sku;
    }

    public Integer getCountedQuantity() {
        return countedQuantity;
    }

    public Integer getExpectedQuantity() {
        return expectedQuantity;
    }

    public Long getObservedStockVersion() {
        return observedStockVersion;
    }

    public String getReason() {
        return reason;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StockCountLine that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
