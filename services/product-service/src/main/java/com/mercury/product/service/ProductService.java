package com.mercury.product.service;

import com.mercury.product.client.InventoryClient;
import com.mercury.product.dto.CreateProductRequest;
import com.mercury.product.dto.InventoryReservationResult;
import com.mercury.product.dto.ProductResponse;
import com.mercury.product.dto.UpdateProductRequest;
import com.mercury.product.exception.DuplicateSkuException;
import com.mercury.product.exception.MissingIdempotencyKeyException;
import com.mercury.product.exception.ProductNotFoundException;
import com.mercury.product.model.Product;
import com.mercury.product.repository.ProductRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProductService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    private final ProductRepository productRepository;
    private final InventoryClient inventoryClient;
    private final ProductCache cache;

    public ProductService(
            ProductRepository productRepository,
            InventoryClient inventoryClient,
            ProductCache cache) {
        this.productRepository = productRepository;
        this.inventoryClient = inventoryClient;
        this.cache = cache;
    }

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {

        if (productRepository.existsBySku(request.sku())) {
            throw new DuplicateSkuException(request.sku());
        }

        Product product = new Product();
        product.setName(request.name());
        product.setSku(request.sku());
        product.setPrice(request.price());
        product.setQuantity(request.quantity());

        try {
            Product savedProduct = productRepository.saveAndFlush(product);
            return ProductResponse.from(savedProduct);
        } catch (DataIntegrityViolationException e) {
            // concurrent create won the race past the existsBySku check
            throw new DuplicateSkuException(request.sku());
        }
    }

    /**
     * Cache first (when enabled), then the database. Not @Transactional on purpose: a cache hit needs no database
     * connection at all, and the repository's own read-only transaction covers the miss.
     */
    public ProductResponse getProduct(UUID id) {

        Optional<ProductResponse> cached = cache.get(id);
        if (cached.isPresent()) {
            return cached.get();
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        ProductResponse response = ProductResponse.from(product);
        cache.put(response);
        return response;
    }

    /** One page, oldest first (stable order). The list is never unbounded: size is capped at {@link #MAX_PAGE_SIZE}. */
    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts(int page, int size) {

        int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return productRepository.findAll(PageRequest.of(
                        Math.max(page, 0), boundedSize, Sort.by("createdAt").ascending().and(Sort.by("id"))))
                .stream()
                .map(ProductResponse::from)
                .toList();
    }

    @Transactional
    public ProductResponse updateProduct(
            UUID id,
            UpdateProductRequest request) {

        Product existingProduct = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        existingProduct.setName(request.name());
        existingProduct.setPrice(request.price());
        existingProduct.setQuantity(request.quantity());

        Product updatedProduct = productRepository.save(existingProduct);
        evictAfterCommit(id);

        return ProductResponse.from(updatedProduct);
    }

    @Transactional
    public void deleteProduct(UUID id) {

        Product existingProduct = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        productRepository.delete(existingProduct);
        evictAfterCommit(id);
    }

    /**
     * Evicting before the commit would let a concurrent reader re-cache the old row; after the commit the
     * next read loads the new one. (Redis being down is harmless: the TTL bounds staleness.)
     */
    private void evictAfterCommit(UUID id) {
        if (!cache.enabled()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.evict(id);
                }
            });
        } else {
            cache.evict(id);
        }
    }

    /**
     * Reserves stock for a product. Product Service only checks the product exists; stock
     * itself is owned by Inventory Service, which also owns idempotency, so the caller's key
     * is forwarded untouched.
     *
     * Deliberately not @Transactional: no database connection should stay open while we
     * wait on another service. Inventory is never created here: a product without an
     * inventory record surfaces as Inventory's own 404.
     */
    public InventoryReservationResult reserveProduct(
            UUID productId,
            int quantity,
            String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }

        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException(productId);
        }

        return inventoryClient.reserveInventory(productId, quantity, idempotencyKey);
    }
}
