package com.example.order_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * requestNo 是可选、不含空白且区分大小写的幂等键。同键同参数返回原订单，不同参数产生冲突。
 */
public record CreateOrderRequest(
        @NotNull @Positive Long userId,
        @NotNull @Positive Long productId,
        @Min(1) Integer quantity,
        @Size(max = 64) @Pattern(regexp = "\\S+", message = "must not be empty or contain whitespace") String requestNo
) {

    public CreateOrderRequest {
        if (quantity == null) {
            quantity = 1;
        }
    }
}
