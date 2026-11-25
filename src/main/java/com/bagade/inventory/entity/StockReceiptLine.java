package com.bagade.inventory.entity;

import com.bagade.catalog.entity.Sku;
import com.bagade.purchasing.entity.PurchaseOrderLine;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "stock_receipt_line")
public class StockReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_receipt_id", nullable = false)
    private StockReceipt stockReceipt;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku_id", nullable = false)
    private Sku sku;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_line_id")
    private PurchaseOrderLine purchaseOrderLine;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "printed_mrp", precision = 12, scale = 2)
    private BigDecimal printedMrp;

    public StockReceiptLine() {
    }

    public StockReceiptLine(
            int lineNumber,
            Sku sku,
            PurchaseOrderLine purchaseOrderLine,
            int quantity,
            BigDecimal unitCost,
            BigDecimal printedMrp) {
        this.lineNumber = lineNumber;
        this.sku = sku;
        this.purchaseOrderLine = purchaseOrderLine;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.printedMrp = printedMrp;
    }

    public UUID getId() {
        return id;
    }

    public StockReceipt getStockReceipt() {
        return stockReceipt;
    }

    public void setStockReceipt(StockReceipt stockReceipt) {
        this.stockReceipt = stockReceipt;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public Sku getSku() {
        return sku;
    }

    public PurchaseOrderLine getPurchaseOrderLine() {
        return purchaseOrderLine;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public BigDecimal getPrintedMrp() {
        return printedMrp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StockReceiptLine that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
