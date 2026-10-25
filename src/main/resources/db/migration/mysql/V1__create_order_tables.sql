create table product
(
    product_id bigint         primary key,
    name       varchar(100)   not null,
    price      decimal(12, 2) not null default 0,
    stock      bigint         not null default 0
) engine = InnoDB;

create table `order`
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
    constraint fk_order_product foreign key (product_id) references product (product_id),
    index idx_order_user_id (user_id)
) engine = InnoDB;
