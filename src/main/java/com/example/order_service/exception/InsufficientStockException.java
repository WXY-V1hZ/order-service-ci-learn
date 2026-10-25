package com.example.order_service.exception;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(long productId) {
        super("Insufficient stock for product: " + productId);
    }
}
