package com.bagade.inventory.entity;

import com.bagade.purchasing.entity.PurchaseOrder;
import com.bagade.purchasing.entity.Vendor;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "stock_receipt")
public class StockReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "receipt_number", nullable = false, unique = true, length = 50)
    private String receiptNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    @Column(name = "vendor_invoice_ref", length = 150)
    private String vendorInvoiceRef;

    @Column(name = "received_date", nullable = false)
    private LocalDate receivedDate;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt = Instant.now();

    @Column(name = "posted_by", nullable = false, updatable = false)
    private UUID postedBy;

    @OneToMany(mappedBy = "stockReceipt", cascade = CascadeType.ALL)
    @OrderBy("lineNumber ASC, id ASC")
    private List<StockReceiptLine> lines = new ArrayList<>();

    public StockReceipt() {
    }

    public StockReceipt(
            String receiptNumber,
            Vendor vendor,
            PurchaseOrder purchaseOrder,
            String vendorInvoiceRef,
            LocalDate receivedDate,
            Instant postedAt,
            UUID postedBy) {
        this.receiptNumber = receiptNumber;
        this.vendor = vendor;
        this.purchaseOrder = purchaseOrder;
        this.vendorInvoiceRef = vendorInvoiceRef;
        this.receivedDate = receivedDate;
        this.postedAt = postedAt;
        this.postedBy = postedBy;
    }

    public void addLine(StockReceiptLine line) {
        line.setStockReceipt(this);
        this.lines.add(line);
    }

    public UUID getId() {
        return id;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public Vendor getVendor() {
        return vendor;
    }

    public PurchaseOrder getPurchaseOrder() {
        return purchaseOrder;
    }

    public String getVendorInvoiceRef() {
        return vendorInvoiceRef;
    }

    public LocalDate getReceivedDate() {
        return receivedDate;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public UUID getPostedBy() {
        return postedBy;
    }

    public List<StockReceiptLine> getLines() {
        return lines;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StockReceipt that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
