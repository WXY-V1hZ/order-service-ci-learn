package com.example.order_service.domain;

import java.math.BigDecimal;

public record Order(
        long orderId,
        long userId,
        long productId,
        int quantity,
        BigDecimal amount,
        String status
) {
}
