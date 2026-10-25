package com.example.order_service.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency key is already used for a different request");
    }
}
