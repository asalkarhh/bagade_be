package com.bagade.inventory.service;

import com.bagade.catalog.entity.Sku;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.error.BarcodeConflictException;
import com.bagade.common.error.BarcodeNotFoundException;
import com.bagade.common.error.RequestValidationException;
import com.bagade.common.error.ResourceNotFoundException;
import com.bagade.inventory.dto.BarcodeLookupResponse;
import com.bagade.inventory.dto.BarcodeMappingResponse;
import com.bagade.inventory.dto.EligibleBatchResponse;
import com.bagade.inventory.dto.RegisterBarcodeRequest;
import com.bagade.inventory.entity.BarcodeMapping;
import com.bagade.inventory.entity.BarcodeSourceType;
import com.bagade.inventory.entity.StockBatch;
import com.bagade.inventory.entity.StockCondition;
import com.bagade.inventory.repository.BarcodeMappingRepository;
import com.bagade.inventory.repository.BatchBalanceRepository;
import com.bagade.inventory.repository.BatchBalanceRow;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class BarcodeService {

    private final BarcodeMappingRepository barcodeMappingRepository;
    private final SkuRepository skuRepository;
    private final BatchBalanceRepository batchBalanceRepository;
    private final TransactionTemplate requiresNewTx;
    private final TransactionTemplate readOnlyTx;

    public BarcodeService(
            BarcodeMappingRepository barcodeMappingRepository,
            SkuRepository skuRepository,
            BatchBalanceRepository batchBalanceRepository,
            PlatformTransactionManager transactionManager) {
        this.barcodeMappingRepository = barcodeMappingRepository;
        this.skuRepository = skuRepository;
        this.batchBalanceRepository = batchBalanceRepository;

        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnlyTx.setReadOnly(true);
    }

    public record RegistrationResult(boolean created, BarcodeMappingResponse response) {}

    public RegistrationResult registerBrandBarcode(RegisterBarcodeRequest request, UUID actorUserId) {
        String code = CatalogValues.normalizeRequiredText(request.code());

        RegistrationResult existingResult = readOnlyTx.execute(status -> {
            Sku sku = skuRepository.findById(request.skuId())
                    .orElseThrow(() -> new ResourceNotFoundException("SKU not found"));
            if (!sku.isActive()) {
                throw new RequestValidationException("skuId", "SKU is inactive");
            }
            return barcodeMappingRepository.findByCode(code)
                    .map(existing -> resolveExistingBrandRegistration(existing, request.skuId()))
                    .orElse(null);
        });
        if (existingResult != null) {
            return existingResult;
        }

        try {
            BarcodeMappingResponse created = requiresNewTx.execute(status -> {
                Sku sku = skuRepository.getReferenceById(request.skuId());
                BarcodeMapping mapping = new BarcodeMapping(
                        code,
                        BarcodeSourceType.BRAND,
                        sku,
                        null,
                        actorUserId
                );
                BarcodeMapping saved = barcodeMappingRepository.saveAndFlush(mapping);
                return toMappingResponse(saved);
            });
            return new RegistrationResult(true, created);
        } catch (DataIntegrityViolationException ex) {
            RegistrationResult conflictOrExisting = readOnlyTx.execute(status ->
                    barcodeMappingRepository.findByCode(code)
                            .map(existing -> resolveExistingBrandRegistration(existing, request.skuId()))
                            .orElse(null)
            );
            if (conflictOrExisting != null) {
                return conflictOrExisting;
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public BarcodeLookupResponse lookupBarcode(String rawCode) {
        String code = CatalogValues.normalizeOptionalText(rawCode);
        if (code == null) {
            throw new RequestValidationException("code", "code parameter is required");
        }

        BarcodeMapping mapping = barcodeMappingRepository.findByCodeWithDetails(code)
                .orElseThrow(() -> new BarcodeNotFoundException("Barcode not found: " + code));

        Sku sku = mapping.getSku();
        List<BatchBalanceRow> skuBalanceRows = batchBalanceRepository.findBatchBalancesForSkuIds(List.of(sku.getId()));

        long totalSellable = 0;
        Map<UUID, EligibleBatchResponse> sellableBatchesById = new LinkedHashMap<>();
        Set<Optional<BigDecimal>> distinctSellableMrps = new HashSet<>();

        for (BatchBalanceRow row : skuBalanceRows) {
            if (row.condition() == StockCondition.SELLABLE && row.quantity() > 0) {
                totalSellable += row.quantity();
                BigDecimal normalizedMrp = row.printedMrp() != null
                        ? row.printedMrp().setScale(2, RoundingMode.HALF_UP)
                        : null;
                distinctSellableMrps.add(Optional.ofNullable(normalizedMrp));
                sellableBatchesById.put(row.batchId(), new EligibleBatchResponse(
                        row.batchId(),
                        row.receivedDate(),
                        CatalogValues.formatPrice(row.printedMrp()),
                        row.quantity(),
                        row.internalBarcode()
                ));
            }
        }

        if (mapping.getSourceType() == BarcodeSourceType.INTERNAL) {
            StockBatch batch = mapping.getBatch();
            long batchSellable = batchBalanceRepository.sumSellableByBatchId(batch.getId());
            return new BarcodeLookupResponse(
                    mapping.getCode(),
                    BarcodeSourceType.INTERNAL,
                    sku.getId(),
                    sku.getProduct().getName(),
                    sku.getSize(),
                    CatalogValues.formatPrice(sku.getDefaultSellingPrice()),
                    totalSellable,
                    batch.getId(),
                    CatalogValues.formatPrice(batch.getPrintedMrp()),
                    batchSellable,
                    false,
                    List.of()
            );
        }

        boolean requiresBatchSelection = distinctSellableMrps.size() > 1;
        List<EligibleBatchResponse> eligibleBatches = requiresBatchSelection
                ? new ArrayList<>(sellableBatchesById.values())
                : List.of();

        return new BarcodeLookupResponse(
                mapping.getCode(),
                BarcodeSourceType.BRAND,
                sku.getId(),
                sku.getProduct().getName(),
                sku.getSize(),
                CatalogValues.formatPrice(sku.getDefaultSellingPrice()),
                totalSellable,
                null,
                null,
                null,
                requiresBatchSelection,
                eligibleBatches
        );
    }

    private RegistrationResult resolveExistingBrandRegistration(BarcodeMapping existing, UUID requestedSkuId) {
        if (existing.getSourceType() != BarcodeSourceType.BRAND) {
            throw new BarcodeConflictException("Barcode is already assigned as an internal batch code");
        }
        if (!Objects.equals(existing.getSku().getId(), requestedSkuId)) {
            throw new BarcodeConflictException("Barcode is already mapped to another SKU");
        }
        return new RegistrationResult(false, toMappingResponse(existing));
    }

    private BarcodeMappingResponse toMappingResponse(BarcodeMapping mapping) {
        return new BarcodeMappingResponse(
                mapping.getId(),
                mapping.getCode(),
                mapping.getSourceType(),
                mapping.getSku().getId(),
                mapping.getBatch() != null ? mapping.getBatch().getId() : null
        );
    }
}
