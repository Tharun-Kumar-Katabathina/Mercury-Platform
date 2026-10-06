package com.mercury.product.service;

import com.mercury.product.dto.CreateProductRequest;
import com.mercury.product.dto.ProductResponse;
import com.mercury.product.dto.UpdateProductRequest;
import com.mercury.product.exception.DuplicateSkuException;
import com.mercury.product.exception.ProductNotFoundException;
import com.mercury.product.model.Product;
import com.mercury.product.repository.ProductRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
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

    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID id) {

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts() {

        return productRepository.findAll()
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

        return ProductResponse.from(updatedProduct);
    }

    @Transactional
    public void deleteProduct(UUID id) {

        Product existingProduct = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        productRepository.delete(existingProduct);
    }
}
