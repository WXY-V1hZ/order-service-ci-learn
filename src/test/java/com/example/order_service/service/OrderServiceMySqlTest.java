package com.example.order_service.service;

import com.example.order_service.domain.Order;
import com.example.order_service.dto.CreateOrderRequest;
import com.example.order_service.dto.CreateOrderResponse;
import com.example.order_service.exception.InsufficientStockException;
import com.example.order_service.exception.IdempotencyConflictException;
import com.example.order_service.repository.OrderRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("mysql")
@Testcontainers
@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderServiceMySqlTest {

    // MySQL 8.0.46：固定镜像摘要，避免同一个 tag 的内容随时间变化。
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            "mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b")
            .withDatabaseName("order_service_test")
            .withUsername("order_test")
            .withPassword("order_test")
            .withCommand("--sql-mode=STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        // 覆盖 prod 的环境变量连接配置，始终连接本次测试创建的独立容器。
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void prepareDatabase() {
        jdbc.update("delete from order_request");
        jdbc.update("delete from `order`");
        jdbc.update("delete from product");
        jdbc.update("insert into product (product_id, name, price, stock) values (?, ?, ?, ?)",
                10L, "MySQL test product", new BigDecimal("19.90"), 10);
    }

    @ParameterizedTest(name = "quantity={0}, expectedAmount={1}, remainingStock={2}")
    @CsvSource({"2, 39.80, 8", "10, 199.00, 0"})
    void createOrder_whenProductIsAvailable_commitsOrderAndStockDeduction(
            int quantity, String expectedAmount, long remainingStock) {
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, quantity, "mysql-created-1");

        CreateOrderResponse response = orderService.createOrder(request);

        // Service 调用已返回、事务已提交，再通过真实 Repository 和数据库检查结果。
        Order stored = orderRepository.findByRequestNo("mysql-created-1").orElseThrow();
        long stock = jdbc.queryForObject(
                "select stock from product where product_id = ?", Long.class, 10L);
        assertAll("Committed order",
                () -> assertTrue(response.orderId() > 0),
                () -> assertEquals(new CreateOrderResponse(
                        stored.orderId(), 1L, 10L, quantity, new BigDecimal(expectedAmount), "CREATED"), response),
                () -> assertEquals(new Order(
                        response.orderId(), 1L, 10L, quantity, new BigDecimal(expectedAmount), "CREATED"), stored),
                () -> assertEquals(remainingStock, stock),
                () -> assertEquals(1L, orderCount()));
    }

    @ParameterizedTest(name = "stock={0}, requestedQuantity={1}")
    @CsvSource({"0, 1", "10, 11"})
    void createOrder_whenStockIsInsufficient_keepsStockAndOrdersUnchanged(long initialStock, int quantity) {
        jdbc.update("update product set stock = ? where product_id = ?", initialStock, 10L);
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, quantity, "mysql-insufficient-1");

        assertThrows(InsufficientStockException.class, () -> orderService.createOrder(request));

        assertAll("Rejected order leaves no changes",
                () -> assertEquals(initialStock, stock()),
                () -> assertEquals(0L, orderCount()));
    }

    @Test
    void createOrder_whenAmountOverflows_rollsBackStockDeduction() {
        jdbc.update("update product set price = ? where product_id = ?", new BigDecimal("9999999999.99"), 10L);
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, 2, "mysql-overflow-1");

        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                () -> orderService.createOrder(request));

        // MySQL 1264 表示数字超出列范围，避免把其他数据库故障当作预期的订单写入失败。
        SQLException cause = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertAll("Order INSERT fails and the stock UPDATE is rolled back",
                () -> assertEquals(1264, cause.getErrorCode()),
                () -> assertEquals(10L, stock()),
                () -> assertEquals(0L, orderCount()));
    }

    @Test
    void orderRepository_whenRequestNoIsDuplicated_databaseRejectsSecondOrder() {
        // 在 Repository 边界测试数据库唯一约束，绕过 Service 的幂等查询。
        long firstId = orderRepository.insert(
                1L, 10L, 1, new BigDecimal("19.90"), "CREATED", "mysql-unique-1");

        assertThrows(DuplicateKeyException.class, () -> orderRepository.insert(
                1L, 10L, 1, new BigDecimal("19.90"), "CREATED", "mysql-unique-1"));

        assertAll("Only the original order exists",
                () -> assertEquals(1L, orderCount()),
                () -> assertEquals(firstId,
                        orderRepository.findByRequestNo("mysql-unique-1").orElseThrow().orderId()));
    }

    @Test
    void orderRepository_whenProductDoesNotExist_databaseRejectsOrphanOrder() {
        // Service 会提前检查商品；直接调用 Repository 才能验证数据库外键本身。
        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                () -> orderRepository.insert(
                        1L, 999L, 1, new BigDecimal("19.90"), "CREATED", "mysql-orphan-1"));

        SQLException cause = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertAll("Foreign key prevents orphan orders",
                () -> assertEquals(1452, cause.getErrorCode()),
                () -> assertEquals(0L, orderCount()));
    }

    private long stock() {
        return jdbc.queryForObject("select stock from product where product_id = ?", Long.class, 10L);
    }

    @ParameterizedTest(name = "conflicting user={0}, product={1}, quantity={2}")
    @CsvSource({"2, 10, 2", "1, 11, 2", "1, 10, 3"})
    void createOrder_whenSameKeyHasDifferentPayload_rejectsConflictWithoutChangingData(
            long userId, long productId, int quantity) {
        CreateOrderResponse original = orderService.createOrder(
                new CreateOrderRequest(1L, 10L, 2, "mysql-conflict-1"));

        assertThrows(IdempotencyConflictException.class, () -> orderService.createOrder(
                new CreateOrderRequest(userId, productId, quantity, "mysql-conflict-1")));

        assertAll("Conflicting retries cannot change the original order",
                () -> assertEquals(8L, stock()),
                () -> assertEquals(1L, orderCount()),
                () -> assertEquals(new Order(original.orderId(), 1L, 10L, 2,
                        new BigDecimal("39.80"), "CREATED"),
                        orderRepository.findByRequestNo("mysql-conflict-1").orElseThrow()));
    }

    private long orderCount() {
        return jdbc.queryForObject("select count(*) from `order`", Long.class);
    }

    @ParameterizedTest(name = "initialStock={0}, remainingStock={1}")
    @CsvSource({"1, 0", "10, 9"})
    void createOrder_whenSameKeyIsSubmittedConcurrently_returnsOneOrderToAllCallers(
            long initialStock, long remainingStock) throws Exception {
        jdbc.update("update product set stock = ? where product_id = ?", initialStock, 10L);
        List<CreateOrderRequest> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(new CreateOrderRequest(1L, 10L, 1, "mysql-concurrent-same"));
        }

        List<OrderAttempt> attempts = runConcurrently(requests);

        for (OrderAttempt attempt : attempts) {
            assertNull(attempt.rejection(), "An identical retry must return the original order");
        }
        CreateOrderResponse first = attempts.getFirst().response();
        for (OrderAttempt attempt : attempts) {
            assertEquals(first, attempt.response());
        }
        assertAll("All retries share one committed order",
                () -> assertEquals(remainingStock, stock()),
                () -> assertEquals(1L, orderCount()),
                () -> assertEquals(1, first.quantity()),
                () -> assertEquals(new BigDecimal("19.90"), first.amount()));
    }

    private List<OrderAttempt> runConcurrently(List<CreateOrderRequest> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderAttempt>> futures = new ArrayList<>();
        try {
            for (CreateOrderRequest request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "Start signal timed out");
                    try {
                        return new OrderAttempt(orderService.createOrder(request), null);
                    } catch (InsufficientStockException | IdempotencyConflictException rejection) {
                        // 仅收集业务拒绝；SQL 异常、锁超时等仍通过 Future 使测试失败。
                        return new OrderAttempt(null, rejection);
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers were not ready in time");
            start.countDown();
            List<OrderAttempt> attempts = new ArrayList<>();
            for (Future<OrderAttempt> future : futures) {
                attempts.add(future.get(20, TimeUnit.SECONDS));
            }
            return attempts;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Workers did not terminate in time");
        }
    }

    @Test
    void createOrder_whenRetryArrivesAfterStockAndPriceChange_returnsOriginalOrder() {
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, 10, "mysql-retry-after-commit");
        CreateOrderResponse original = orderService.createOrder(request);
        jdbc.update("update product set price = ? where product_id = ?", new BigDecimal("25.00"), 10L);

        CreateOrderResponse retry = orderService.createOrder(request);

        assertAll("Retry returns the committed result even when stock is exhausted",
                () -> assertEquals(original, retry),
                () -> assertEquals(new BigDecimal("199.00"), retry.amount()),
                () -> assertEquals(0L, stock()),
                () -> assertEquals(1L, orderCount()));
    }

    @Test
    void createOrder_whenFirstAttemptRollsBack_sameKeyCanBeRetriedSuccessfully() {
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, 2, "mysql-retry-after-failure");
        jdbc.update("update product set price = ? where product_id = ?", new BigDecimal("9999999999.99"), 10L);
        assertThrows(DataIntegrityViolationException.class, () -> orderService.createOrder(request));
        assertEquals(0L, jdbc.queryForObject("select count(*) from order_request", Long.class).longValue());
        assertEquals(10L, stock());
        jdbc.update("update product set price = ? where product_id = ?", new BigDecimal("19.90"), 10L);

        CreateOrderResponse response = orderService.createOrder(request);

        assertAll("Failed attempts do not permanently reserve the key",
                () -> assertEquals(new BigDecimal("39.80"), response.amount()),
                () -> assertEquals(8L, stock()),
                () -> assertEquals(1L, orderCount()));
    }

    @Test
    void createOrder_whenRequestNoIsAbsent_treatsCallsAsIndependentOrders() {
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, 1, null);

        CreateOrderResponse first = orderService.createOrder(request);
        CreateOrderResponse second = orderService.createOrder(request);

        assertAll("Requests without a key are independent",
                () -> assertNotEquals(first.orderId(), second.orderId()),
                () -> assertEquals(2L, orderCount()),
                () -> assertEquals(8L, stock()));
    }

    @Test
    void createOrder_whenKeyCaseDiffers_treatsKeysAsDistinct() {
        CreateOrderResponse first = orderService.createOrder(new CreateOrderRequest(1L, 10L, 1, "mysql-Case"));
        CreateOrderResponse second = orderService.createOrder(new CreateOrderRequest(1L, 10L, 1, "mysql-case"));

        assertAll("Opaque keys use case-sensitive comparison",
                () -> assertNotEquals(first.orderId(), second.orderId()),
                () -> assertEquals(2L, orderCount()),
                () -> assertEquals(8L, stock()));
    }

    @Test
    void createOrder_whenDistinctKeysCompeteForTenItems_commitsExactlyTenOrders() throws Exception {
        List<CreateOrderRequest> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(new CreateOrderRequest(1L, 10L, 1, "mysql-distinct-" + i));
        }

        List<OrderAttempt> attempts = runConcurrently(requests);

        HashSet<Long> successfulIds = new HashSet<>();
        int rejections = 0;
        for (OrderAttempt attempt : attempts) {
            if (attempt.rejection() == null) {
                successfulIds.add(attempt.response().orderId());
            } else {
                assertInstanceOf(InsufficientStockException.class, attempt.rejection());
                rejections++;
            }
        }
        assertEquals(10, rejections);
        assertAll("Distinct requests cannot oversell",
                () -> assertEquals(10, successfulIds.size()),
                () -> assertEquals(0L, stock()),
                () -> assertEquals(10L, orderCount()),
                () -> assertEquals(10L, jdbc.queryForObject(
                        "select sum(quantity) from `order`", Long.class).longValue()));
    }

    @Test
    void createOrder_whenDifferentProductsCompeteForOneKey_onlyWinningPayloadIsAccepted() throws Exception {
        jdbc.update("insert into product (product_id, name, price, stock) values (?, ?, ?, ?)",
                11L, "Another product", new BigDecimal("29.90"), 10);
        List<CreateOrderRequest> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            requests.add(new CreateOrderRequest(1L, 10L, 1, "mysql-conflicting-race"));
            requests.add(new CreateOrderRequest(1L, 11L, 1, "mysql-conflicting-race"));
        }

        List<OrderAttempt> attempts = runConcurrently(requests);

        CreateOrderResponse winner = attempts.stream().filter(attempt -> attempt.response() != null)
                .findFirst().orElseThrow().response();
        int successes = 0;
        int conflicts = 0;
        for (int i = 0; i < attempts.size(); i++) {
            OrderAttempt attempt = attempts.get(i);
            if (requests.get(i).productId() == winner.productId()) {
                assertNull(attempt.rejection());
                assertEquals(winner, attempt.response());
                successes++;
            } else {
                assertInstanceOf(IdempotencyConflictException.class, attempt.rejection());
                conflicts++;
            }
        }
        assertEquals(10, successes);
        assertEquals(10, conflicts);
        assertEquals(1L, orderCount());
        assertEquals(winner.productId() == 10L ? 9L : 10L, stock());
        assertEquals(winner.productId() == 11L ? 9L : 10L, jdbc.queryForObject(
                "select stock from product where product_id = ?", Long.class, 11L).longValue());
    }

    @Test
    void createOrder_whenCallerHasOlderSnapshot_readsRecentlyCommittedOriginalOrder() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CreateOrderRequest request = new CreateOrderRequest(1L, 10L, 1, "mysql-old-snapshot");
        try {
            CreateOrderResponse retry = new TransactionTemplate(transactionManager).execute(status -> {
                // MySQL 默认 REPEATABLE READ：先在外层事务建立“没有订单”的快照。
                assertEquals(0L, orderCount());
                try {
                    CreateOrderResponse original = executor.submit(() -> orderService.createOrder(request))
                            .get(20, TimeUnit.SECONDS);
                    CreateOrderResponse repeated = orderService.createOrder(request);
                    assertEquals(original, repeated);
                    return repeated;
                } catch (Exception failure) {
                    throw new AssertionError("Retry must see the newly committed order", failure);
                }
            });
            assertEquals(new BigDecimal("19.90"), retry.amount());
            assertEquals(1L, orderCount());
            assertEquals(9L, stock());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Worker did not terminate in time");
        }
    }

    @Test
    void migration_whenV1AlreadyContainsOrders_backfillsIdempotencyReservations() {
        // 仅在本次 Testcontainers 容器中创建独立升级测试库，不接触外部数据库。
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        admin.execute("create database order_service_upgrade_test");
        try {
            DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                    MYSQL.getJdbcUrl().replace("/order_service_test", "/order_service_upgrade_test"),
                    "root", MYSQL.getPassword());
            Flyway.configure().dataSource(upgradeDataSource).locations("classpath:db/migration/mysql")
                    .target("1").load().migrate();
            JdbcTemplate upgradeJdbc = new JdbcTemplate(upgradeDataSource);
            upgradeJdbc.update("insert into product (product_id, name, price, stock) values (?, ?, ?, ?)",
                    10L, "Existing product", new BigDecimal("19.90"), 8);
            long originalId = new OrderRepository(upgradeJdbc).insert(
                    1L, 10L, 2, new BigDecimal("39.80"), "CREATED", "mysql-legacy");

            Flyway upgraded = Flyway.configure().dataSource(upgradeDataSource)
                    .locations("classpath:db/migration/mysql").load();
            upgraded.migrate();
            upgraded.validate();

            assertEquals(originalId, upgradeJdbc.queryForObject(
                    "select order_id from order_request where request_no = ?", Long.class, "mysql-legacy").longValue());
            assertEquals(1L, upgradeJdbc.queryForObject("select count(*) from `order`", Long.class).longValue());
            assertEquals(8L, upgradeJdbc.queryForObject(
                    "select stock from product where product_id = ?", Long.class, 10L).longValue());
        } finally {
            admin.execute("drop database order_service_upgrade_test");
        }
    }

    private record OrderAttempt(CreateOrderResponse response, RuntimeException rejection) {
    }
}
