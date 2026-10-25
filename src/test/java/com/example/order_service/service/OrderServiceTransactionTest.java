package com.example.order_service.service;

import com.example.order_service.dto.CreateOrderRequest;
import com.example.order_service.dto.CreateOrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:order-transaction-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.h2.console.enabled=false"
})
class OrderServiceTransactionTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareDatabase() {
        // 仅清理本测试专用的 H2 内存库，准备确定的初始状态。
        jdbc.update("delete from order_request");
        jdbc.update("delete from `order`");
        jdbc.update("delete from product");
        jdbc.update("insert into product (product_id, name, price, stock) values (?, ?, ?, ?)",
                10L, "Transaction test product", new BigDecimal("9999999999.99"), 10);
    }

    @Test
    void createOrder_whenOrderInsertFails_rollsBackStockDeduction() {
        // Arrange：单价能存入 decimal(12, 2)，但买 2 件的总额超出订单金额列的范围。
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, 2, null);

        // Act：库存扣减成功后，真实的订单 INSERT 因金额溢出失败。
        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class,
                () -> orderService.createOrder(request));
        assertTrue(exception.getMessage().contains("insert into `order`"),
                "Failure should come from inserting the order");

        // Assert：业务调用已结束，在事务外读取数据库，验证扣库存也被回滚。
        Long stock = jdbc.queryForObject("select stock from product where product_id = ?", Long.class, 10L);
        Long orderCount = jdbc.queryForObject("select count(*) from `order`", Long.class);
        assertEquals(10L, stock.longValue());
        assertEquals(0L, orderCount.longValue());
    }

    @Test
    void createOrder_whenSameKeyIsRetried_onH2ReturnsOriginalOrderWithoutDeductingAgain() {
        // 开发环境也需要支持新增的幂等协调 SQL，不能只验证 MySQL。
        jdbc.update("update product set price = ? where product_id = ?", new BigDecimal("19.90"), 10L);
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, 2, "h2-retry");

        CreateOrderResponse first = orderService.createOrder(request);
        CreateOrderResponse retry = orderService.createOrder(request);

        assertEquals(first, retry);
        assertEquals(8L, jdbc.queryForObject(
                "select stock from product where product_id = ?", Long.class, 10L).longValue());
        assertEquals(1L, jdbc.queryForObject("select count(*) from `order`", Long.class).longValue());
    }
}
