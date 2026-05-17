-- seckill: order idempotency + outbox
ALTER TABLE tb_voucher_order
    ADD UNIQUE KEY uk_user_voucher(user_id, voucher_id);

CREATE TABLE IF NOT EXISTS tb_mq_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic VARCHAR(128) NOT NULL,
    tag VARCHAR(64) NOT NULL,
    body TEXT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-UNSENT 1-SENT',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status_retry(status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- like write-behind persistence
CREATE TABLE IF NOT EXISTS tb_blog_like (
    id BIGINT NOT NULL AUTO_INCREMENT,
    blog_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_blog_user(blog_id, user_id),
    KEY idx_blog(blog_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- comment cursor pagination & tree indexes
ALTER TABLE tb_blog_comments
    ADD INDEX idx_blog_parent_id(blog_id, parent_id, id),
    ADD INDEX idx_parent_id(parent_id),
    ADD INDEX idx_blog_liked(blog_id, liked);
