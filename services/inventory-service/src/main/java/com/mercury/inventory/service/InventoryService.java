package com.mercury.inventory.service;

import com.mercury.inventory.dto.CreateInventoryRequest;
import com.mercury.inventory.dto.InventoryResponse;
import com.mercury.inventory.dto.ReleaseResponse;
import com.mercury.inventory.dto.ReleaseResult;
import com.mercury.inventory.dto.ReservationResponse;
import com.mercury.inventory.dto.ReservationResult;
import com.mercury.inventory.dto.UpdateInventoryRequest;
import com.mercury.inventory.exception.DuplicateInventoryException;
import com.mercury.inventory.exception.IdempotencyKeyMismatchException;
import com.mercury.inventory.exception.InsufficientReservedStockException;
import com.mercury.inventory.exception.InsufficientStockException;
import com.mercury.inventory.exception.InventoryNotFoundException;
import com.mercury.inventory.model.IdempotencyRecord;
import com.mercury.inventory.model.Inventory;
import com.mercury.inventory.repository.IdempotencyRecordRepository;
import com.mercury.inventory.repository.InventoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransactionTemplate transactionTemplate;
    private final JsonMapper jsonMapper;
    private final int reserveMaxAttempts;

    public InventoryService(
            InventoryRepository inventoryRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            TransactionTemplate transactionTemplate,
            JsonMapper jsonMapper,
            @Value("${inventory.reserve.max-attempts:5}") int reserveMaxAttempts) {
        this.inventoryRepository = inventoryRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.transactionTemplate = transactionTemplate;
        this.jsonMapper = jsonMapper;
        this.reserveMaxAttempts = reserveMaxAttempts;
    }

    @Transactional
    public InventoryResponse createInventory(CreateInventoryRequest request) {

        if (inventoryRepository.existsByProductId(request.productId())) {
            throw new DuplicateInventoryException(request.productId());
        }

        Inventory inventory = new Inventory();
        inventory.setProductId(request.productId());
        inventory.setAvailableQuantity(request.availableQuantity());
        inventory.setReservedQuantity(0);

        try {
            return InventoryResponse.from(inventoryRepository.saveAndFlush(inventory));
        } catch (DataIntegrityViolationException e) {
            // concurrent create won the race past the existsByProductId check
            throw new DuplicateInventoryException(request.productId());
        }
    }

    @Transactional(readOnly = true)
    public InventoryResponse getInventory(UUID productId) {

        return InventoryResponse.from(findByProductId(productId));
    }

    @Transactional
    public InventoryResponse updateInventory(
            UUID productId,
            UpdateInventoryRequest request) {

        Inventory inventory = findByProductId(productId);
        inventory.setAvailableQuantity(request.availableQuantity());

        // flush so a version conflict surfaces here, not after the method returns
        return InventoryResponse.from(inventoryRepository.saveAndFlush(inventory));
    }

    /**
     * Moves {@code quantity} units from available to reserved, at most once per
     * {@code idempotencyKey}. Insufficient stock is a business answer: never retried, never
     * stored, so a later retry is evaluated against current stock.
     */
    public ReservationResult reserveInventory(
            UUID productId, int quantity, String idempotencyKey) {

        IdempotentResult<ReservationResponse> result = executeIdempotently(
                productId, idempotencyKey,
                sha256(productId + ":" + quantity),
                ReservationResponse.class,
                () -> reserveOnce(productId, quantity));

        return new ReservationResult(result.response(), result.replayed());
    }

    /**
     * The reverse of a reservation: moves {@code quantity} units from reserved back to
     * available, at most once per {@code idempotencyKey}. Releasing more than is reserved
     * is refused. The hash is prefixed so a release can never be mistaken for a reservation
     * made with the same key (that is a mismatch, not a replay).
     */
    public ReleaseResult releaseInventory(
            UUID productId, int quantity, String idempotencyKey) {

        IdempotentResult<ReleaseResponse> result = executeIdempotently(
                productId, idempotencyKey,
                sha256("RELEASE:" + productId + ":" + quantity),
                ReleaseResponse.class,
                () -> releaseOnce(productId, quantity));

        return new ReleaseResult(result.response(), result.replayed());
    }

    private record IdempotentResult<T>(T response, boolean replayed) {
    }

    /**
     * Two independent protections work together:
     *  - optimistic locking (@Version) stops concurrent requests from corrupting the stock row;
     *  - the idempotency record stops the SAME logical request from executing twice.
     *
     * Not @Transactional on purpose: every attempt runs in its own transaction so a
     * version conflict re-reads the latest stock (and the latest idempotency record)
     * instead of retrying on stale state.
     */
    private <T> IdempotentResult<T> executeIdempotently(
            UUID productId,
            String idempotencyKey,
            String requestHash,
            Class<T> responseType,
            Supplier<T> operation) {

        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> {
                    Optional<IdempotencyRecord> existing =
                            idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
                    if (existing.isPresent()) {
                        return replay(existing.get(), idempotencyKey, requestHash, responseType);
                    }

                    T response = operation.get();

                    // same transaction as the stock change: both commit, or neither does
                    IdempotencyRecord record = new IdempotencyRecord();
                    record.setIdempotencyKey(idempotencyKey);
                    record.setRequestHash(requestHash);
                    record.setProductId(productId);
                    record.setResponseBody(jsonMapper.writeValueAsString(response));
                    idempotencyRecordRepository.saveAndFlush(record);

                    return new IdempotentResult<>(response, false);
                });
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= reserveMaxAttempts) {
                    throw e;
                }
                backOff(attempt);
            } catch (DataIntegrityViolationException e) {
                // A concurrent request with the same key committed first (unique key).
                // Its result is now visible, so replay it; otherwise this was something else.
                return findReplay(idempotencyKey, requestHash, responseType)
                        .orElseThrow(() -> e);
            }
        }
    }

    private ReservationResponse reserveOnce(UUID productId, int quantity) {

        Inventory inventory = findByProductId(productId);

        if (inventory.getAvailableQuantity() < quantity) {
            throw new InsufficientStockException(
                    productId, quantity, inventory.getAvailableQuantity());
        }

        inventory.setAvailableQuantity(inventory.getAvailableQuantity() - quantity);
        inventory.setReservedQuantity(inventory.getReservedQuantity() + quantity);

        // flush so the version check happens inside this attempt's transaction
        return ReservationResponse.from(inventoryRepository.saveAndFlush(inventory), quantity);
    }

    private ReleaseResponse releaseOnce(UUID productId, int quantity) {

        Inventory inventory = findByProductId(productId);

        if (inventory.getReservedQuantity() < quantity) {
            throw new InsufficientReservedStockException(
                    productId, quantity, inventory.getReservedQuantity());
        }

        inventory.setAvailableQuantity(inventory.getAvailableQuantity() + quantity);
        inventory.setReservedQuantity(inventory.getReservedQuantity() - quantity);

        return ReleaseResponse.from(inventoryRepository.saveAndFlush(inventory), quantity);
    }

    private <T> Optional<IdempotentResult<T>> findReplay(
            String idempotencyKey, String requestHash, Class<T> responseType) {

        return Optional.ofNullable(transactionTemplate.execute(status ->
                idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey)
                        .map(record -> replay(record, idempotencyKey, requestHash, responseType))
                        .orElse(null)));
    }

    private <T> IdempotentResult<T> replay(
            IdempotencyRecord record,
            String idempotencyKey,
            String requestHash,
            Class<T> responseType) {

        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyMismatchException(idempotencyKey);
        }
        return new IdempotentResult<>(
                jsonMapper.readValue(record.getResponseBody(), responseType), true);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 5L * attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying reservation", e);
        }
    }

    private Inventory findByProductId(UUID productId) {
        return inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InventoryNotFoundException(productId));
    }
}
