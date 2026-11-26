CREATE TABLE bike_freight_forwarder (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    contact_name VARCHAR(128) NOT NULL,
    phone VARCHAR(64) NOT NULL,
    address VARCHAR(1000) NOT NULL,
    remark VARCHAR(500) NULL,
    revision INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
