ALTER TABLE bike_production_sku
    ADD COLUMN category VARCHAR(64) NULL COMMENT '外箱标签品类' AFTER upc,
    ADD COLUMN battery_description VARCHAR(100) NULL COMMENT '外箱标签电池说明' AFTER category,
    ADD COLUMN package_quantity INT NULL COMMENT '单箱数量' AFTER battery_description,
    ADD COLUMN carton_size_cm VARCHAR(64) NULL COMMENT '外箱尺寸厘米' AFTER package_quantity,
    ADD COLUMN gross_weight_kg DECIMAL(10,3) NULL COMMENT '毛重千克' AFTER carton_size_cm,
    ADD COLUMN net_weight_kg DECIMAL(10,3) NULL COMMENT '净重千克' AFTER gross_weight_kg,
    ADD CONSTRAINT ck_production_sku_package_quantity CHECK (package_quantity IS NULL OR package_quantity > 0),
    ADD CONSTRAINT ck_production_sku_gross_weight CHECK (gross_weight_kg IS NULL OR gross_weight_kg > 0),
    ADD CONSTRAINT ck_production_sku_net_weight CHECK (net_weight_kg IS NULL OR net_weight_kg > 0),
    ADD CONSTRAINT ck_production_sku_weight_order CHECK (gross_weight_kg IS NULL OR net_weight_kg IS NULL OR gross_weight_kg >= net_weight_kg);

CREATE TABLE bike_production_sku_name (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Name 明细主键',
    sku_id BIGINT NOT NULL COMMENT '所属商品 SKU',
    name VARCHAR(128) NOT NULL COMMENT '标签 NAME，一条记录只属于一个 SKU',
    ean VARCHAR(13) NULL COMMENT 'EAN-13 商品码',
    sn VARCHAR(128) NULL COMMENT '标签序列号',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_production_sku_name_name (name),
    UNIQUE KEY uk_production_sku_name_ean (ean),
    UNIQUE KEY uk_production_sku_name_sn (sn),
    KEY idx_production_sku_name_sku (sku_id, enabled, id),
    CONSTRAINT fk_production_sku_name_sku FOREIGN KEY (sku_id) REFERENCES bike_production_sku(id),
    CONSTRAINT ck_production_sku_name_ean CHECK (ean IS NULL OR ean REGEXP '^[0-9]{13}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品 SKU 的 Name、EAN 与 SN 一对一明细';
