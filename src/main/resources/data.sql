delete from order_request;

delete
from `order`;
delete
from product;

insert into product (product_id, name, price, stock)
values (1, 'iPhone', 7999.00, 10);
insert into product (product_id, name, price, stock)
values (2, 'MacBook', 12999.00, 5);
insert into product (product_id, name, price, stock)
values (3, 'iPad', 3999.00, 0);
