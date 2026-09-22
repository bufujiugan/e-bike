CREATE TABLE bike_after_sales_return (
    shipment_id BIGINT PRIMARY KEY COMMENT '补发条目ID，每项配件独立退回流程',
    case_id BIGINT NOT NULL COMMENT '所属售后案例ID',
    status VARCHAR(32) NOT NULL DEFAULT 'REQUIRED' COMMENT 'REQUIRED/WAITING_CUSTOMER/IN_TRANSIT/RECEIVED/INSPECTED/SCRAPPED/COMPLETED/WAIVED',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '乐观锁版本，每次操作递增',
    warehouse_name VARCHAR(128) NULL COMMENT '退回仓库名称快照',
    recipient VARCHAR(100) NULL COMMENT '退回收件人快照',
    phone VARCHAR(64) NULL COMMENT '退回联系电话快照',
    address_line VARCHAR(500) NULL COMMENT '退回完整地址快照',
    postal_code VARCHAR(32) NULL COMMENT '退回邮编快照',
    carrier VARCHAR(128) NULL COMMENT '客户寄回承运商',
    tracking_no VARCHAR(128) NULL COMMENT '客户寄回运单号',
    sent_at DATETIME(3) NULL COMMENT '客户登记寄回时间',
    received_at DATETIME(3) NULL COMMENT '仓库确认收到时间',
    inspected_at DATETIME(3) NULL COMMENT '技术人员确认检测时间',
    inspection_result VARCHAR(2000) NULL COMMENT '客户可见旧件检测结果',
    disposition_note VARCHAR(1000) NULL COMMENT '报废、完成或豁免理由',
    completed_at DATETIME(3) NULL COMMENT '退回流程完成或豁免时间',
    updated_by BIGINT NULL COMMENT '最后操作人ID，迁移历史为空',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
    KEY idx_bike_after_sales_return_case (case_id,status),
    CONSTRAINT fk_bike_after_sales_return_shipment FOREIGN KEY (shipment_id) REFERENCES bike_after_sales_shipment(id),
    CONSTRAINT fk_bike_after_sales_return_case FOREIGN KEY (case_id) REFERENCES bike_after_sales_case(id),
    CONSTRAINT ck_bike_after_sales_return_status CHECK (status IN ('REQUIRED','WAITING_CUSTOMER','IN_TRANSIT','RECEIVED','INSPECTED','SCRAPPED','COMPLETED','WAIVED')),
    CONSTRAINT ck_bike_after_sales_return_revision CHECK (revision > 0),
    CONSTRAINT ck_bike_after_sales_return_tracking CHECK (status != 'IN_TRANSIT' OR (carrier IS NOT NULL AND CHAR_LENGTH(TRIM(carrier)) > 0 AND tracking_no IS NOT NULL AND CHAR_LENGTH(TRIM(tracking_no)) > 0 AND sent_at IS NOT NULL)),
    CONSTRAINT ck_bike_after_sales_return_completion CHECK (status NOT IN ('COMPLETED','WAIVED') OR (completed_at IS NOT NULL AND disposition_note IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后旧件退回、收件、检测和处置';

-- Preserve only facts known from legacy shipment rows; never invent a return address or tracking number.
INSERT INTO bike_after_sales_return(shipment_id,case_id,status,received_at)
SELECT id,case_id,CASE WHEN old_part_return_received_at IS NULL THEN 'REQUIRED' ELSE 'RECEIVED' END,old_part_return_received_at
FROM bike_after_sales_shipment WHERE old_part_return_required=1;
