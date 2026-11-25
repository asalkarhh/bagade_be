package com.bagade.billing.entity;

import com.bagade.catalog.entity.Sku;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "bill_line")
public class BillLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_revision_id", nullable = false)
    private BillRevision billRevision;

    @Column(name = "logical_line_id", nullable = false)
    private UUID logicalLineId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku_id", nullable = false)
    private Sku sku;

    @Column(name = "sku_code_snapshot", nullable = false, length = 100)
    private String skuCodeSnapshot;

    @Column(name = "product_name_snapshot", nullable = false, length = 200)
    private String productNameSnapshot;

    @Column(name = "size_snapshot", length = 50)
    private String sizeSnapshot;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "initial_returned_quantity", nullable = false)
    private int initialReturnedQuantity;

    @Column(name = "returned_quantity", nullable = false)
    private int returnedQuantity;

    @Column(name = "unit_selling_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitSellingPrice;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "returned_discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal returnedDiscountAmount;

    @Column(name = "returned_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal returnedAmount;

    public BillLine() {
    }

    public BillLine(
            UUID logicalLineId,
            int lineNumber,
            Sku sku,
            String skuCodeSnapshot,
            String productNameSnapshot,
            String sizeSnapshot,
            int quantity,
            BigDecimal unitSellingPrice,
            BigDecimal discountAmount,
            BigDecimal lineTotal) {
        this(logicalLineId, lineNumber, sku, skuCodeSnapshot, productNameSnapshot, sizeSnapshot,
                quantity, 0, unitSellingPrice, discountAmount, lineTotal,
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
    }

    public BillLine(
            UUID logicalLineId,
            int lineNumber,
            Sku sku,
            String skuCodeSnapshot,
            String productNameSnapshot,
            String sizeSnapshot,
            int quantity,
            int returnedQuantity,
            BigDecimal unitSellingPrice,
            BigDecimal discountAmount,
            BigDecimal lineTotal,
            BigDecimal returnedDiscountAmount,
            BigDecimal returnedAmount) {
        this.logicalLineId = logicalLineId;
        this.lineNumber = lineNumber;
        this.sku = sku;
        this.skuCodeSnapshot = skuCodeSnapshot;
        this.productNameSnapshot = productNameSnapshot;
        this.sizeSnapshot = sizeSnapshot;
        this.quantity = quantity;
        this.initialReturnedQuantity = returnedQuantity;
        this.returnedQuantity = returnedQuantity;
        this.unitSellingPrice = unitSellingPrice;
        this.discountAmount = discountAmount;
        this.lineTotal = lineTotal;
        this.returnedDiscountAmount = returnedDiscountAmount;
        this.returnedAmount = returnedAmount;
    }

    public int getInitialReturnedQuantity() {
        return initialReturnedQuantity;
    }

    public int getReturnableQuantity() {
        return quantity - returnedQuantity;
    }

    public void applyReturn(int returnQuantity, BigDecimal discountDeltaAbs, BigDecimal netAmountDeltaAbs) {
        if (returnQuantity <= 0 || this.returnedQuantity + returnQuantity > this.quantity) {
            throw new IllegalArgumentException("Return quantity out of bounds");
        }
        this.returnedQuantity += returnQuantity;
        this.returnedDiscountAmount = this.returnedDiscountAmount.add(discountDeltaAbs);
        this.returnedAmount = this.returnedAmount.add(netAmountDeltaAbs);
    }

    public BigDecimal getReturnedDiscountAmount() {
        return returnedDiscountAmount;
    }

    public BigDecimal getReturnedAmount() {
        return returnedAmount;
    }

    public BigDecimal getRemainingNetAmount() {
        return lineTotal.subtract(returnedAmount);
    }

    public BigDecimal getRemainingDiscountAmount() {
        return discountAmount.subtract(returnedDiscountAmount);
    }

    public UUID getId() {
        return id;
    }

    public BillRevision getBillRevision() {
        return billRevision;
    }

    public void setBillRevision(BillRevision billRevision) {
        this.billRevision = billRevision;
    }

    public UUID getLogicalLineId() {
        return logicalLineId;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public Sku getSku() {
        return sku;
    }

    public String getSkuCodeSnapshot() {
        return skuCodeSnapshot;
    }

    public String getProductNameSnapshot() {
        return productNameSnapshot;
    }

    public String getSizeSnapshot() {
        return sizeSnapshot;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getReturnedQuantity() {
        return returnedQuantity;
    }

    public BigDecimal getUnitSellingPrice() {
        return unitSellingPrice;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BillLine that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
