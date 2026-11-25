package com.bagade.inventory.service;

import com.bagade.catalog.entity.Sku;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.error.LabelNotFoundException;
import com.bagade.common.error.ResourceNotFoundException;
import com.bagade.inventory.dto.BatchLabelResponse;
import com.bagade.inventory.entity.BarcodeMapping;
import com.bagade.inventory.entity.BarcodeSourceType;
import com.bagade.inventory.entity.StockBatch;
import com.bagade.inventory.repository.BarcodeMappingRepository;
import com.bagade.inventory.repository.StockBatchRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class BatchLabelService {

    private final StockBatchRepository stockBatchRepository;
    private final BarcodeMappingRepository barcodeMappingRepository;
    private final TransactionTemplate requiresNewTx;
    private final TransactionTemplate readOnlyTx;
    private final String shopName;

    public BatchLabelService(
            StockBatchRepository stockBatchRepository,
            BarcodeMappingRepository barcodeMappingRepository,
            PlatformTransactionManager transactionManager,
            @Value("${app.shop.name:Bagade Clothing}") String shopName) {
        this.stockBatchRepository = stockBatchRepository;
        this.barcodeMappingRepository = barcodeMappingRepository;
        this.shopName = shopName;

        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnlyTx.setReadOnly(true);
    }

    public BatchLabelResponse generateOrGetLabel(UUID batchId, UUID actorUserId) {
        BatchLabelResponse existing = readOnlyTx.execute(status -> {
            StockBatch batch = stockBatchRepository.findByIdWithSkuAndProduct(batchId)
                    .orElseThrow(() -> new ResourceNotFoundException("Stock batch not found"));
            return barcodeMappingRepository.findByBatchIdAndSourceType(batchId, BarcodeSourceType.INTERNAL)
                    .map(mapping -> toLabelResponse(batch, mapping.getCode()))
                    .orElse(null);
        });
        if (existing != null) {
            return existing;
        }

        try {
            return requiresNewTx.execute(status -> {
                StockBatch batch = stockBatchRepository.findByIdWithSkuAndProduct(batchId)
                        .orElseThrow(() -> new ResourceNotFoundException("Stock batch not found"));
                String code = String.format("BGD%08d", barcodeMappingRepository.nextInternalBarcodeSequence());
                BarcodeMapping mapping = new BarcodeMapping(
                        code,
                        BarcodeSourceType.INTERNAL,
                        batch.getSku(),
                        batch,
                        actorUserId
                );
                barcodeMappingRepository.saveAndFlush(mapping);
                return toLabelResponse(batch, code);
            });
        } catch (DataIntegrityViolationException ex) {
            BatchLabelResponse concurrent = readOnlyTx.execute(status -> {
                StockBatch batch = stockBatchRepository.findByIdWithSkuAndProduct(batchId)
                        .orElseThrow(() -> new ResourceNotFoundException("Stock batch not found"));
                BarcodeMapping mapping = barcodeMappingRepository
                        .findByBatchIdAndSourceType(batchId, BarcodeSourceType.INTERNAL)
                        .orElseThrow(() -> ex);
                return toLabelResponse(batch, mapping.getCode());
            });
            return concurrent;
        }
    }

    @Transactional(readOnly = true)
    public BatchLabelResponse getLabel(UUID batchId) {
        StockBatch batch = stockBatchRepository.findByIdWithSkuAndProduct(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Stock batch not found"));

        BarcodeMapping mapping = barcodeMappingRepository
                .findByBatchIdAndSourceType(batchId, BarcodeSourceType.INTERNAL)
                .orElseThrow(() -> new LabelNotFoundException("No generated label found for batch"));

        return toLabelResponse(batch, mapping.getCode());
    }

    private BatchLabelResponse toLabelResponse(StockBatch batch, String code) {
        Sku sku = batch.getSku();
        return new BatchLabelResponse(
                batch.getId(),
                code,
                shopName,
                sku.getProduct().getName(),
                sku.getSize(),
                CatalogValues.formatPrice(sku.getDefaultSellingPrice()),
                code,
                code
        );
    }
}
