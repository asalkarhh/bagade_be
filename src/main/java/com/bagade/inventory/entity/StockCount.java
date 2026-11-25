package com.bagade.inventory.entity;

import com.bagade.catalog.entity.Category;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "stock_count")
public class StockCount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockCountScope scope;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockCountStatus status = StockCountStatus.DRAFT;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "posted_by")
    private UUID postedBy;

    @OneToMany(mappedBy = "stockCount", cascade = CascadeType.ALL)
    @OrderBy("lineNumber ASC, id ASC")
    private List<StockCountLine> lines = new ArrayList<>();

    public StockCount() {
    }

    public StockCount(StockCountScope scope, Category category, Instant createdAt, UUID createdBy) {
        this.scope = scope;
        this.category = category;
        this.status = StockCountStatus.DRAFT;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.createdBy = createdBy;
    }

    public void addLine(StockCountLine line) {
        line.setStockCount(this);
        this.lines.add(line);
    }

    public void touchUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void markPosted(Instant postedAt, UUID postedBy) {
        this.status = StockCountStatus.POSTED;
        this.postedAt = postedAt;
        this.postedBy = postedBy;
        this.updatedAt = postedAt;
    }

    public UUID getId() {
        return id;
    }

    public StockCountScope getScope() {
        return scope;
    }

    public Category getCategory() {
        return category;
    }

    public StockCountStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public UUID getPostedBy() {
        return postedBy;
    }

    public List<StockCountLine> getLines() {
        return lines;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StockCount that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
