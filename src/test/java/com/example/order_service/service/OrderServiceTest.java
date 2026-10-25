package com.example.order_service.service;

import com.example.order_service.dto.CreateOrderRequest;
import com.example.order_service.dto.CreateOrderResponse;
import com.example.order_service.exception.InsufficientStockException;
import com.example.order_service.exception.ProductNotFoundException;
import com.example.order_service.repository.OrderRepository;
import com.example.order_service.repository.OrderRequestRepository;
import com.example.order_service.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderRequestRepository orderRequestRepository;

    @Test
    void createOrder_whenProductDoesNotExist_throwsProductNotFoundException() {
        // Arrange：准备一个不存在的商品，以及真实的业务服务。
        when(productRepository.findPrice(999L)).thenReturn(Optional.empty());
        OrderService orderService = new OrderService(productRepository, orderRepository, orderRequestRepository);
        CreateOrderRequest request = new CreateOrderRequest(1L, 999L, 1, null);

        // Act + Assert：执行下单，并断言它抛出约定的业务异常。
        assertThrows(ProductNotFoundException.class, () -> orderService.createOrder(request));
        verifyNoInteractions(orderRepository);
    }

    @ParameterizedTest(name = "price={0}, quantity={1}, expectedAmount={2}")
    @CsvSource({
            "19.90, 1, 19.90",
            "19.90, 2, 39.80",
            "0.10, 3, 0.30"
    })
    void createOrder_whenProductIsAvailable_returnsAndPersistsCalculatedAmount(
            String unitPrice, int quantity, String expectedAmount) {
        // Arrange：从业务示例给出预期金额，库存扣减成功，生成订单号 1001。
        when(productRepository.findPrice(10L)).thenReturn(Optional.of(new BigDecimal(unitPrice)));
        when(productRepository.decreaseStock(10L, quantity)).thenReturn(1);
        when(orderRepository.insert(eq(1L), eq(10L), eq(quantity), any(BigDecimal.class), eq("CREATED"), isNull()))
                .thenReturn(1001L);
        OrderService orderService = new OrderService(productRepository, orderRepository, orderRequestRepository);
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, quantity, null);

        // Act：执行真实的下单业务逻辑。
        CreateOrderResponse response = orderService.createOrder(request);

        // Assert：同时检查返回值，以及交给持久化边界的金额，防止只算对响应。
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(orderRepository).insert(
                eq(1L), eq(10L), eq(quantity), amountCaptor.capture(), eq("CREATED"), isNull());
        BigDecimal expected = new BigDecimal(expectedAmount);
        assertAll("Created order",
                () -> assertEquals(expected, response.amount()),
                () -> assertEquals(expected, amountCaptor.getValue()),
                () -> assertEquals(1001L, response.orderId()),
                () -> assertEquals(1L, response.userId()),
                () -> assertEquals(10L, response.productId()),
                () -> assertEquals(quantity, response.quantity()),
                () -> assertEquals("CREATED", response.status()));
    }

    @Test
    void createOrder_whenStockIsInsufficient_rejectsOrderWithoutInserting() {
        // Arrange：商品存在，但数据库报告扣库存未成功（影响 0 行）。
        when(productRepository.findPrice(10L)).thenReturn(Optional.of(new BigDecimal("19.90")));
        when(productRepository.decreaseStock(10L, 2)).thenReturn(0);
        OrderService orderService = new OrderService(productRepository, orderRepository, orderRequestRepository);
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, 2, null);

        // Act + Assert：失败类型正确，并且不能继续创建订单。
        assertThrows(InsufficientStockException.class, () -> orderService.createOrder(request));
        verifyNoInteractions(orderRepository);
    }
}
