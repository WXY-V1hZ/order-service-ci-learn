package com.example.order_service.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRequestRepository {

    private final JdbcTemplate jdbc;

    public OrderRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 必须在业务事务中调用。同一键的插入或无变化更新取得行锁，直到事务结束。
     * 返回 null 表示本事务负责创建订单；其他事务须等首次创建提交或回滚。
     */
    public Long lockAndFindOrderId(String requestNo) {
        jdbc.update("""
                insert into order_request (request_no) values (?)
                on duplicate key update request_no = request_no
                """, requestNo);
        return jdbc.queryForObject(
                "select order_id from order_request where request_no = ? for update", Long.class, requestNo);
    }

    public void complete(String requestNo, long orderId) {
        int updated = jdbc.update(
                "update order_request set order_id = ? where request_no = ?", orderId, requestNo);
        if (updated != 1) {
            throw new IllegalStateException("Idempotency reservation is missing");
        }
    }
}
