package com.mercury.product.controller;

import com.mercury.product.dto.CreateProductRequest;
import com.mercury.product.dto.InventoryReservationResponse;
import com.mercury.product.dto.InventoryReservationResult;
import com.mercury.product.dto.ProductResponse;
import com.mercury.product.dto.ReserveProductRequest;
import com.mercury.product.dto.UpdateProductRequest;
import com.mercury.product.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(
            @Valid @RequestBody CreateProductRequest request) {

        return productService.createProduct(request);
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable UUID id) {

        return productService.getProduct(id);
    }

    @GetMapping
    public List<ProductResponse> getAllProducts() {

        return productService.getAllProducts();
    }

    @PutMapping("/{id}")
    public ProductResponse updateProduct(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProductRequest request) {

        return productService.updateProduct(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProduct(@PathVariable UUID id) {

        productService.deleteProduct(id);
    }

    @PostMapping("/{productId}/reserve")
    public ResponseEntity<InventoryReservationResponse> reserveProduct(
            @PathVariable UUID productId,
            // optional here so a missing key gets Mercury's own MISSING_IDEMPOTENCY_KEY error
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReserveProductRequest request) {

        InventoryReservationResult result = productService.reserveProduct(
                productId, request.quantity(), idempotencyKey);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.reservation());
    }
}
