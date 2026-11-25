package com.bagade.common.idempotency;

import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.error.IdempotencyConflictException;
import com.bagade.common.error.RequestValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRecordRepository idempotencyRecordRepository, ObjectMapper objectMapper) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
    }

    public record StoredResponse<T>(int status, T body) {}

    public String validateKey(String rawKey) {
        String trimmed = CatalogValues.normalizeOptionalText(rawKey);
        if (trimmed == null) {
            throw new RequestValidationException("Idempotency-Key", "Idempotency-Key header is required");
        }
        if (trimmed.length() > 200) {
            throw new RequestValidationException("Idempotency-Key", "Idempotency-Key must not exceed 200 characters");
        }
        return trimmed;
    }

    public String computeRequestHash(Object canonicalPayload) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(canonicalPayload);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to compute idempotency request hash", ex);
        }
    }

    public <T> Optional<StoredResponse<T>> findReplay(
            String operation,
            String idempotencyKey,
            String requestHash,
            Class<T> responseType) {
        Optional<IdempotencyRecord> existing = idempotencyRecordRepository
                .findByOperationAndIdempotencyKey(operation, idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord record = existing.get();
        if (!Objects.equals(record.getRequestHash(), requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used with different request parameters"
            );
        }
        try {
            T body = objectMapper.readValue(record.getResponseBody(), responseType);
            return Optional.of(new StoredResponse<>(record.getResponseStatus(), body));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize stored idempotency response", ex);
        }
    }

    public void saveResult(
            String operation,
            String idempotencyKey,
            String requestHash,
            int status,
            Object responseBody,
            UUID actorUserId) {
        try {
            String json = objectMapper.writeValueAsString(responseBody);
            IdempotencyRecord record = new IdempotencyRecord(
                    operation,
                    idempotencyKey,
                    requestHash,
                    status,
                    json,
                    actorUserId
            );
            idempotencyRecordRepository.saveAndFlush(record);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize idempotency response", ex);
        }
    }
}
