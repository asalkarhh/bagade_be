package com.bagade.inventory.repository;

import com.bagade.inventory.entity.StockReceiptLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface StockReceiptLineRepository extends JpaRepository<StockReceiptLine, UUID> {
}
