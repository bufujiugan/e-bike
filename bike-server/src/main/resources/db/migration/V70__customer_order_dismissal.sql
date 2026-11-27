CREATE TABLE bike_customer_order_dismissal (
    user_id BIGINT NOT NULL,
    order_kind VARCHAR(8) NOT NULL,
    document_id BIGINT NOT NULL,
    dismissed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, order_kind, document_id),
    CONSTRAINT fk_customer_order_dismissal_user
        FOREIGN KEY (user_id) REFERENCES bike_sys_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
