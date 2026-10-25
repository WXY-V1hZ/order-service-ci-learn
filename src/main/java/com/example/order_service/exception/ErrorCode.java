package com.example.order_service.exception;

public enum ErrorCode {
    INVALID_REQUEST,
    PRODUCT_NOT_FOUND,
    INSUFFICIENT_STOCK,
    DUPLICATE_REQUEST,
    IDEMPOTENCY_CONFLICT,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    INTERNAL_ERROR
}
