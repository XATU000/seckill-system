CREATE TABLE IF NOT EXISTS seckill_goods (
    id BIGINT PRIMARY KEY,
    stock INT
);

INSERT INTO seckill_goods VALUES (1, 100) ON DUPLICATE KEY UPDATE stock = VALUES(stock);

CREATE TABLE IF NOT EXISTS seckill_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    goods_id BIGINT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    create_time DATETIME NOT NULL,
    INDEX idx_goods_id (goods_id),
    UNIQUE KEY uk_goods_user (goods_id, user_id)
);