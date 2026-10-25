package com.example.order_service.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class ProductRepository {

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BigDecimal> findPrice(long productId) {
        return jdbc.query(
                        "select price from product where product_id = ?",
                        (rs, rowNum) -> rs.getBigDecimal("price"),
                        productId)
                .stream()
                .findFirst();
    }

    /**
     * 一条 SQL 内完成判断和扣减，并发下不会超卖。
     *
     * @return 受影响行数，0 表示商品不存在或库存不足
     */
    public int decreaseStock(long productId, int quantity) {
        return jdbc.update(
                "update product set stock = stock - ? where product_id = ? and stock >= ?",
                quantity, productId, quantity);
    }
}
