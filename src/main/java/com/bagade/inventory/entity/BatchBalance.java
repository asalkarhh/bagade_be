package com.bagade.inventory.entity;

import jakarta.persistence.*;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "batch_balance")
public class BatchBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private StockBatch batch;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_condition", nullable = false, length = 30)
    private StockCondition stockCondition;

    @Column(nullable = false)
    private int quantity;

    public BatchBalance() {
    }

    public BatchBalance(StockBatch batch, StockCondition stockCondition, int quantity) {
        this.batch = batch;
        this.stockCondition = stockCondition;
        this.quantity = quantity;
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

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BatchBalance that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
