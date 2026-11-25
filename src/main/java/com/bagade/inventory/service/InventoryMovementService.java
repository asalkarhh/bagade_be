package com.bagade.inventory.service;

import com.bagade.billing.entity.SaleAllocation;
import com.bagade.billing.entity.SaleAllocationReversal;
import com.bagade.inventory.entity.BatchBalance;
import com.bagade.inventory.entity.MovementType;
import com.bagade.inventory.entity.SkuStockState;
import com.bagade.inventory.entity.StockBatch;
import com.bagade.inventory.entity.StockCondition;
import com.bagade.inventory.entity.StockMovement;
import com.bagade.inventory.entity.StockReceiptLine;
import com.bagade.inventory.repository.BatchBalanceRepository;
import com.bagade.inventory.repository.SkuStockStateRepository;
import com.bagade.inventory.repository.StockMovementRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared stock-locking, balance-mutation, and immutable stock-movement ledger operations.
 */
@Service
public class InventoryMovementService {

    private final SkuStockStateRepository skuStockStateRepository;
    private final BatchBalanceRepository batchBalanceRepository;
    private final StockMovementRepository stockMovementRepository;

    public InventoryMovementService(
            SkuStockStateRepository skuStockStateRepository,
            BatchBalanceRepository batchBalanceRepository,
            StockMovementRepository stockMovementRepository) {
        this.skuStockStateRepository = skuStockStateRepository;
        this.batchBalanceRepository = batchBalanceRepository;
        this.stockMovementRepository = stockMovementRepository;
    }

    public List<SkuStockState> lockOrInitializeStockStates(Set<UUID> sortedSkuIds) {
        if (sortedSkuIds.isEmpty()) {
            return List.of();
        }
        List<SkuStockState> locked = skuStockStateRepository.findBySkuIdInOrderBySkuIdAscForUpdate(sortedSkuIds);
        if (locked.size() == sortedSkuIds.size()) {
            return locked;
        }
        Set<UUID> existingIds = locked.stream().map(SkuStockState::getSkuId).collect(Collectors.toSet());
        for (UUID skuId : sortedSkuIds) {
            if (!existingIds.contains(skuId)) {
                skuStockStateRepository.saveAndFlush(new SkuStockState(skuId));
            }
        }
        return skuStockStateRepository.findBySkuIdInOrderBySkuIdAscForUpdate(sortedSkuIds);
    }

    public void incrementStockVersions(Collection<SkuStockState> states) {
        for (SkuStockState state : states) {
            state.incrementStockVersion();
        }
    }

    public BatchBalance recordReceipt(
            StockBatch batch,
            int quantity,
            StockReceiptLine receiptLine,
            Instant postedAt,
            UUID actorUserId) {
        BatchBalance balance = batchBalanceRepository.save(new BatchBalance(batch, StockCondition.SELLABLE, quantity));
        stockMovementRepository.save(new StockMovement(
                batch,
                StockCondition.SELLABLE,
                MovementType.RECEIPT,
                quantity,
                receiptLine,
                postedAt,
                actorUserId
        ));
        return balance;
    }

    public int recordSaleDeduction(
            BatchBalance sellableBalance,
            int allocatedQuantity,
            SaleAllocation saleAllocation,
            Instant postedAt,
            UUID actorUserId) {
        int newQty = sellableBalance.getQuantity() - allocatedQuantity;
        if (newQty < 0) {
            throw new IllegalStateException("Batch balance would become negative for batch " + sellableBalance.getBatch().getId());
        }
        sellableBalance.setQuantity(newQty);
        stockMovementRepository.save(StockMovement.forSale(
                sellableBalance.getBatch(),
                allocatedQuantity,
                saleAllocation,
                postedAt,
                actorUserId
        ));
        return newQty;
    }

    public record BatchConditionKey(UUID batchId, StockCondition condition) {}

    public int recordSaleReversal(
            StockBatch batch,
            StockCondition condition,
            MovementType movementType,
            int restoredQuantity,
            SaleAllocationReversal reversal,
            Map<BatchConditionKey, BatchBalance> lockedBalancesByKey,
            Instant postedAt,
            UUID actorUserId) {
        BatchConditionKey key = new BatchConditionKey(batch.getId(), condition);
        BatchBalance balance = lockedBalancesByKey.get(key);
        if (balance == null) {
            balance = batchBalanceRepository.saveAndFlush(new BatchBalance(batch, condition, 0));
            lockedBalancesByKey.put(key, balance);
        }
        int newQty = balance.getQuantity() + restoredQuantity;
        balance.setQuantity(newQty);

        stockMovementRepository.save(StockMovement.forSaleReversal(
                batch,
                condition,
                movementType,
                restoredQuantity,
                reversal,
                postedAt,
                actorUserId
        ));
        return newQty;
    }
}
