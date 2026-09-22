CREATE TABLE bike_after_sales_solution_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '方案版本ID',
    case_id BIGINT NOT NULL COMMENT '所属案例',
    revision INT NOT NULL COMMENT '案例内递增版本',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/APPROVED/SUPERSEDED/LEGACY',
    document_json JSON NOT NULL COMMENT '技术判断、原因、维修、客户操作、保修、收费责任及条目快照',
    created_by BIGINT NULL COMMENT '方案编写人，旧资料迁移为空',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '版本记录时间，迁移不推测历史编写时间',
    confirmed_by BIGINT NULL COMMENT '技术确认人',
    confirmed_at DATETIME(3) NULL COMMENT '技术确认时间',
    approved_by BIGINT NULL COMMENT '负责人审批人',
    approved_at DATETIME(3) NULL COMMENT '负责人审批时间',
    UNIQUE KEY uk_bike_after_sales_solution_revision(case_id,revision),
    CONSTRAINT fk_bike_after_sales_solution_case FOREIGN KEY(case_id) REFERENCES bike_after_sales_case(id),
    CONSTRAINT ck_bike_after_sales_solution_status CHECK(status IN ('DRAFT','CONFIRMED','APPROVED','SUPERSEDED','LEGACY')),
    CONSTRAINT ck_bike_after_sales_solution_revision CHECK(revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后技术方案及审批版本';

CREATE TABLE bike_after_sales_solution_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '本版本配件条目ID',
    version_id BIGINT NOT NULL COMMENT '所属方案版本',
    part_code VARCHAR(128) NOT NULL COMMENT '配件编号，在案例历次版本内维持相同身份',
    part_name VARCHAR(255) NOT NULL COMMENT '配件名称快照',
    quantity INT NOT NULL COMMENT '本案例累计批准补发数量，包含既有计划与已发数量',
    unit_charge DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '每件客户承担费用，0为免费',
    return_required TINYINT NOT NULL DEFAULT 0 COMMENT '新补发是否要求退回旧件',
    UNIQUE KEY uk_bike_after_sales_solution_part(version_id,part_code),
    CONSTRAINT fk_bike_after_sales_solution_item_version FOREIGN KEY(version_id) REFERENCES bike_after_sales_solution_version(id),
    CONSTRAINT ck_bike_after_sales_solution_item_qty CHECK(quantity>0),
    CONSTRAINT ck_bike_after_sales_solution_item_charge CHECK(unit_charge>=0),
    CONSTRAINT ck_bike_after_sales_solution_item_return CHECK(return_required IN(0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='方案版本明确审批的配件和收费';

ALTER TABLE bike_after_sales_shipment
    ADD COLUMN solution_item_id BIGINT NULL COMMENT '实际执行的已审批方案条目，历史未关联为空',
    ADD COLUMN approval_source VARCHAR(32) NOT NULL DEFAULT 'VERSIONED' COMMENT 'VERSIONED或LEGACY_UNLINKED或LEGACY_REVIEWED',
    ADD KEY idx_bike_after_sales_shipment_solution(solution_item_id),
    ADD CONSTRAINT fk_bike_after_sales_shipment_solution FOREIGN KEY(solution_item_id) REFERENCES bike_after_sales_solution_item(id),
    ADD CONSTRAINT ck_bike_after_sales_shipment_source CHECK(approval_source IN('VERSIONED','LEGACY_UNLINKED','LEGACY_REVIEWED'));
UPDATE bike_after_sales_shipment SET approval_source='LEGACY_UNLINKED';

-- Preserve old narrative verbatim; no approval identity, fee or part list can be inferred from it.
INSERT INTO bike_after_sales_solution_version(case_id,revision,status,document_json)
SELECT id,1,'LEGACY',JSON_OBJECT('technicalJudgment',technical_judgment,'solution',solution,'internalNote',internal_note,'warrantyStatus',warranty_status,'parts',JSON_ARRAY())
FROM bike_after_sales_case WHERE solution IS NOT NULL AND CHAR_LENGTH(TRIM(solution))>0;
