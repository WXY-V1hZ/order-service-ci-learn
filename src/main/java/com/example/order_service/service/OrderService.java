package com.example.order_service.service;

import com.example.order_service.domain.Order;
import com.example.order_service.dto.CreateOrderRequest;
import com.example.order_service.dto.CreateOrderResponse;
import com.example.order_service.exception.InsufficientStockException;
import com.example.order_service.exception.IdempotencyConflictException;
import com.example.order_service.exception.ProductNotFoundException;
import com.example.order_service.repository.OrderRepository;
import com.example.order_service.repository.OrderRequestRepository;
import com.example.order_service.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class OrderService {

    public static final String STATUS_CREATED = "CREATED";

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderRequestRepository orderRequestRepository;

    public OrderService(ProductRepository productRepository, OrderRepository orderRepository,
                        OrderRequestRepository orderRequestRepository) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.orderRequestRepository = orderRequestRepository;
    }

    /**
     * 扣库存和写订单在同一个事务里，任一步失败都会整体回滚。
     * 库存通过带条件的 update 原子扣减，并发请求不会超卖。
     * 带幂等键的请求先锁定键，再检查原订单；键的占用、扣库存和订单写入一并提交或回滚。
     */
    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        long productId = request.productId();

        if (request.requestNo() != null) {
            Long existingId = orderRequestRepository.lockAndFindOrderId(request.requestNo());
            if (existingId != null) {
                Order order = orderRepository.findByIdForUpdate(existingId)
                        .orElseThrow(() -> new IllegalStateException("Idempotent order is missing"));
                if (order.userId() != request.userId() || order.productId() != request.productId()
                        || order.quantity() != request.quantity()) {
                    throw new IdempotencyConflictException();
                }
                log.info("Idempotent retry, requestNo={}, orderId={}", request.requestNo(), order.orderId());
                return toResponse(order);
            }
        }

        BigDecimal price = productRepository.findPrice(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        int updated = productRepository.decreaseStock(productId, request.quantity());
        if (updated == 0) {
            boolean stillExists = productRepository.findPrice(productId).isPresent();
            if (!stillExists) {
                throw new ProductNotFoundException(productId);
            }
            throw new InsufficientStockException(productId);
        }

        BigDecimal amount = price.multiply(BigDecimal.valueOf(request.quantity()))
                .setScale(2, RoundingMode.HALF_UP);

        long orderId = orderRepository.insert(
                request.userId(), productId, request.quantity(), amount, STATUS_CREATED, request.requestNo());

        if (request.requestNo() != null) {
            orderRequestRepository.complete(request.requestNo(), orderId);
        }

        log.info("Order created, orderId={}, userId={}, productId={}, quantity={}, amount={}",
                orderId, request.userId(), productId, request.quantity(), amount);

        return new CreateOrderResponse(
                orderId, request.userId(), productId, request.quantity(), amount, STATUS_CREATED);
    }

    private CreateOrderResponse toResponse(Order order) {
        return new CreateOrderResponse(
                order.orderId(), order.userId(), order.productId(), order.quantity(), order.amount(), order.status());
    }
}
