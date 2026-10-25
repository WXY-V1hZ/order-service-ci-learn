package com.example.order_service.repository;

import com.example.order_service.domain.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Optional;

@Repository
public class OrderRepository {

    private static final RowMapper<Order> ORDER_ROW_MAPPER = (rs, rowNum) -> new Order(
            rs.getLong("order_id"), rs.getLong("user_id"), rs.getLong("product_id"),
            rs.getInt("quantity"), rs.getBigDecimal("amount"), rs.getString("status"));

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long userId, long productId, int quantity, BigDecimal amount, String status, String requestNo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into `order` (user_id, product_id, quantity, amount, status, request_no) "
                            + "values (?, ?, ?, ?, ?, ?)",
                    new String[]{"order_id"});
            ps.setLong(1, userId);
            ps.setLong(2, productId);
            ps.setInt(3, quantity);
            ps.setBigDecimal(4, amount);
            ps.setString(5, status);
            if (requestNo == null) {
                ps.setNull(6, Types.VARCHAR);
            } else {
                ps.setString(6, requestNo);
            }
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert order did not return a generated key");
        }
        return key.longValue();
    }

    public Optional<Order> findByRequestNo(String requestNo) {
        return jdbc.query(
                        """
                                select order_id, user_id, product_id, quantity, amount, status
                                from `order`
                                where request_no = ?
                                """,
                        ORDER_ROW_MAPPER,
                        requestNo)
                .stream()
                .findFirst();
    }

    public Optional<Order> findByIdForUpdate(long orderId) {
        // 当前读：即使调用方事务先前建立过快照，也读取刚提交的原订单。
        return jdbc.query("""
                        select order_id, user_id, product_id, quantity, amount, status
                        from `order` where order_id = ? for update
                        """, ORDER_ROW_MAPPER, orderId)
                .stream().findFirst();
    }
}
