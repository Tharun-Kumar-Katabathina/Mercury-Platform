package com.mercury.product.exception;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku) {
        super("Product with SKU already exists: " + sku);
    }
}