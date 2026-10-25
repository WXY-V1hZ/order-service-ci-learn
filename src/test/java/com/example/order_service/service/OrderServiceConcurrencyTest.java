package com.example.order_service.service;

import com.example.order_service.dto.CreateOrderRequest;
import com.example.order_service.exception.InsufficientStockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:order-concurrency-test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.maximum-pool-size=20",
        "spring.sql.init.mode=always",
        "spring.h2.console.enabled=false"
})
class OrderServiceConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareDatabase() {
        jdbc.update("delete from order_request");
        jdbc.update("delete from `order`");
        jdbc.update("delete from product");
        jdbc.update("insert into product (product_id, name, price, stock) values (?, ?, ?, ?)",
                10L, "Concurrency test product", new BigDecimal("19.90"), 10);
    }

    @Test
    void createOrder_whenTwentyRequestsCompeteForTenItems_neverOversells() throws Exception {
        int requestCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try {
            // Arrange：每个任务先报到，再等待统一放行；不使用 sleep 猜测线程进度。
            for (int i = 0; i < requestCount; i++) {
                results.add(executor.submit(() -> {
                    CreateOrderRequest request = new CreateOrderRequest(1L, 10L, 1, null);
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "Start signal timed out");
                    try {
                        // 每个线程通过 Spring 代理调用服务，使用各自的业务事务。
                        orderService.createOrder(request);
                        return true;
                    } catch (InsufficientStockException exception) {
                        // 只把库存不足视为预期失败；其他异常由 Future.get() 传回测试线程。
                        return false;
                    }
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers were not ready in time");

            // Act：统一放行，等待每个任务结束后再查询数据库。
            start.countDown();
            int successfulOrders = 0;
            int insufficientStockRequests = 0;
            for (Future<Boolean> result : results) {
                if (result.get(15, TimeUnit.SECONDS)) {
                    successfulOrders++;
                } else {
                    insufficientStockRequests++;
                }
            }

            // Assert：响应结果和持久化结果都必须符合预期，不能仅检查库存非负。
            assertEquals(10, successfulOrders);
            assertEquals(10, insufficientStockRequests);
            assertEquals(0L, jdbc.queryForObject(
                    "select stock from product where product_id = ?", Long.class, 10L).longValue());
            assertEquals(10L, jdbc.queryForObject(
                    "select count(*) from `order` where product_id = ?", Long.class, 10L).longValue());
            assertEquals(10L, jdbc.queryForObject(
                    "select coalesce(sum(quantity), 0) from `order` where product_id = ?",
                    Long.class, 10L).longValue());
        } finally {
            // 测试失败也要放行等待中的线程，并停止线程池。
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Workers did not terminate in time");
        }
    }
}
