package com.example.order_service.controller;

import com.example.order_service.dto.CreateOrderRequest;
import com.example.order_service.dto.CreateOrderResponse;
import com.example.order_service.exception.GlobalExceptionHandler;
import com.example.order_service.exception.InsufficientStockException;
import com.example.order_service.exception.IdempotencyConflictException;
import com.example.order_service.exception.ProductNotFoundException;
import com.example.order_service.service.OrderService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void createOrder_whenFieldsAreInvalid_returnsBadRequestWithoutCallingService(
            String scenario, String requestBody, String invalidField) throws Exception {
        // JSON 能被解析，但字段违反校验规则；错误需要指向具体字段。
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details." + invalidField).isNotEmpty());

        // 非法请求应在参数校验阶段被拒绝，不进入下单业务。
        verifyNoInteractions(orderService);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("quantity is zero", """
                        {"userId":1,"productId":10,"quantity":0}
                        """, "quantity"),
                Arguments.of("quantity is negative", """
                        {"userId":1,"productId":10,"quantity":-1}
                        """, "quantity"),
                Arguments.of("userId is missing", """
                        {"productId":10,"quantity":1}
                        """, "userId"),
                Arguments.of("userId is null", """
                        {"userId":null,"productId":10,"quantity":1}
                        """, "userId"),
                Arguments.of("userId is zero", """
                        {"userId":0,"productId":10,"quantity":1}
                        """, "userId"),
                Arguments.of("userId is negative", """
                        {"userId":-1,"productId":10,"quantity":1}
                        """, "userId"),
                Arguments.of("productId is missing", """
                        {"userId":1,"quantity":1}
                        """, "productId"),
                Arguments.of("productId is null", """
                        {"userId":1,"productId":null,"quantity":1}
                        """, "productId"),
                Arguments.of("productId is zero", """
                        {"userId":1,"productId":0,"quantity":1}
                        """, "productId"),
                Arguments.of("productId is negative", """
                        {"userId":1,"productId":-1,"quantity":1}
                        """, "productId"),
                Arguments.of("requestNo exceeds 64 characters", """
                        {"userId":1,"productId":10,"quantity":1,"requestNo":"%s"}
                        """.formatted("r".repeat(65)), "requestNo"),
                Arguments.of("requestNo is empty", """
                        {"userId":1,"productId":10,"quantity":1,"requestNo":""}
                        """, "requestNo"),
                Arguments.of("requestNo is blank", """
                        {"userId":1,"productId":10,"quantity":1,"requestNo":"   "}
                        """, "requestNo"),
                Arguments.of("requestNo contains whitespace", """
                        {"userId":1,"productId":10,"quantity":1,"requestNo":"request 1"}
                        """, "requestNo"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedRequests")
    void createOrder_whenRequestIsAccepted_bindsFieldsAndReturnsCreatedOrder(
            String scenario, String requestBody, CreateOrderRequest expectedRequest) throws Exception {
        // Service 的结果是夹具；本测试验证字段绑定和响应序列化，不验证金额计算。
        CreateOrderResponse response = new CreateOrderResponse(
                1001L, 1L, 10L, 1, new BigDecimal("19.90"), "CREATED");
        when(orderService.createOrder(expectedRequest)).thenReturn(response);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.orderId").value(1001))
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.productId").value(10))
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.amount").value(19.90))
                .andExpect(jsonPath("$.status").value("CREATED"));

        // 不只看 201：确认默认值及客户端字段确实传给了 Service。
        verify(orderService).createOrder(expectedRequest);
    }

    static Stream<Arguments> acceptedRequests() {
        return Stream.of(
                Arguments.of("quantity minimum is accepted and requestNo is preserved", """
                        {"userId":1,"productId":10,"quantity":1,"requestNo":"web-1"}
                        """, new CreateOrderRequest(1L, 10L, 1, "web-1")),
                Arguments.of("missing quantity defaults to one", """
                        {"userId":1,"productId":10}
                        """, new CreateOrderRequest(1L, 10L, 1, null)),
                Arguments.of("null quantity defaults to one", """
                        {"userId":1,"productId":10,"quantity":null}
                        """, new CreateOrderRequest(1L, 10L, 1, null)),
                Arguments.of("requestNo accepts exactly 64 characters", """
                        {"userId":1,"productId":10,"quantity":1,"requestNo":"%s"}
                        """.formatted("r".repeat(64)), new CreateOrderRequest(1L, 10L, 1, "r".repeat(64))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "{",
            "{\"userId\":1,\"productId\":10,\"quantity\":\"many\"}"
    })
    void createOrder_whenBodyCannotBeRead_returnsBadRequestWithoutCallingService(String body) throws Exception {
        // 请求体缺失、JSON 语法错误、字段类型错误属于解析失败，与字段校验分开。
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("businessFailures")
    void createOrder_whenBusinessFails_mapsExceptionToHttpResponse(
            String scenario, RuntimeException failure, int expectedStatus, String expectedCode) throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, 1, null);
        when(orderService.createOrder(request)).thenThrow(failure);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"productId":10,"quantity":1}
                                """))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode));

        verify(orderService).createOrder(request);
    }

    static Stream<Arguments> businessFailures() {
        return Stream.of(
                Arguments.of("missing product", new ProductNotFoundException(10L), 404, "PRODUCT_NOT_FOUND"),
                Arguments.of("insufficient stock", new InsufficientStockException(10L), 409, "INSUFFICIENT_STOCK"),
                Arguments.of("idempotency payload conflict", new IdempotencyConflictException(), 409, "IDEMPOTENCY_CONFLICT"),
                Arguments.of("duplicate key", new DuplicateKeyException("duplicate-key"), 409, "DUPLICATE_REQUEST"));
    }

    @ParameterizedTest
    @MethodSource("unexpectedFailures")
    void createOrder_whenUnexpectedFailureOccurs_returnsGenericErrorWithoutLeakingInternalDetails(
            RuntimeException failure) throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, 1, null);
        when(orderService.createOrder(request)).thenThrow(failure);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"productId":10,"quantity":1}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(content().string(not(containsString("database-secret"))));

        verify(orderService).createOrder(request);
    }

    static Stream<RuntimeException> unexpectedFailures() {
        return Stream.of(new IllegalStateException("database-secret"),
                new DataIntegrityViolationException("database-secret"));
    }
}
