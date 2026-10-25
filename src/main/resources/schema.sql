create table if not exists product
(
    product_id bigint         primary key,
    name       varchar(100)   not null,
    price      decimal(12, 2) not null default 0,
    stock      bigint         not null default 0
);

-- `order` 是 SQL 关键字，H2 需要 MODE=MySQL 才支持反引号
create table if not exists `order`
(
    order_id   bigint         auto_increment primary key,
    user_id    bigint         not null,
    product_id bigint         not null,
    quantity   int            not null,
    amount     decimal(12, 2) not null,
    status     varchar(20)    not null,
    request_no varchar(64),
    created_at timestamp      not null default current_timestamp,
    constraint uk_order_request_no unique (request_no),
    constraint fk_order_product foreign key (product_id) references product (product_id)
);

create index if not exists idx_order_user_id on `order` (user_id);

create table if not exists order_request
(
    request_no varchar(64) primary key,
    order_id   bigint,
    constraint fk_order_request_order foreign key (order_id) references `order` (order_id)
);
