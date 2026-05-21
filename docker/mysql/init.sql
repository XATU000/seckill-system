CREATE DATABASE IF NOT EXISTS seckill DEFAULT CHARACTER SET utf8mb4;

GRANT ALL PRIVILEGES ON seckill.* TO 'seckill'@'%';
FLUSH PRIVILEGES;

USE seckill;

CREATE TABLE IF NOT EXISTS goods (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    stock INT NOT NULL
);

CREATE TABLE IF NOT EXISTS seckill_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    goods_id BIGINT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    create_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=pending 1=paid 2=cancelled',
    INDEX idx_goods_id (goods_id),
    UNIQUE KEY uk_goods_user (goods_id, user_id)
);

INSERT IGNORE INTO goods VALUES (1, 'iPhone 15', 5999.00, 100);
INSERT IGNORE INTO goods VALUES (2, 'MacBook Pro', 15999.00, 50);
