-- 幂等键按原始字符串精确比较，区分大小写及尾部空格。
alter table `order`
    modify request_no varchar(64) character set utf8mb4 collate utf8mb4_0900_bin;

create table order_request
(
    request_no varchar(64) character set utf8mb4 collate utf8mb4_0900_bin primary key,
    order_id   bigint,
    constraint fk_order_request_order foreign key (order_id) references `order` (order_id)
) engine = InnoDB;

-- 从已有 V1 数据升级时，重试仍能找回原订单。
insert into order_request (request_no, order_id)
select request_no, order_id from `order` where request_no is not null;
