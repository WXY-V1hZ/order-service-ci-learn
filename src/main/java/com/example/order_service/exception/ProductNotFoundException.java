package com.example.order_service.exception;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(long productId) {
        super("Product not found: " + productId);
    }
}
