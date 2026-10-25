package com.example.order_service.dto;

import java.math.BigDecimal;

public record CreateOrderResponse(
        long orderId,
        long userId,
        long productId,
        int quantity,
        BigDecimal amount,
        String status
) {
}
