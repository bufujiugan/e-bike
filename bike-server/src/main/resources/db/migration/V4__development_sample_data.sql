INSERT INTO bike_product_model(model_code, model_name, enabled)
VALUES ('CITY-A', '城市通勤款', 1), ('MTB-B', '山地越野款', 1), ('FOLD-C', '折叠便携款', 1), ('LONG-D', '长续航款', 1)
ON DUPLICATE KEY UPDATE model_name = VALUES(model_name), enabled = VALUES(enabled);

INSERT INTO bike_supplier(supplier_code, supplier_name, contact_name, contact_phone)
VALUES ('SUP-001', '深圳动力科技', '周工', '13800000001')
ON DUPLICATE KEY UPDATE supplier_name = VALUES(supplier_name);

INSERT INTO bike_customer(customer_code, customer_name, contact_name, contact_phone)
VALUES ('CUS-001', 'ABC Bike Co., Ltd.', 'Alice', '13900000001'), ('CUS-002', 'Global E-Bike', 'Bob', '13900000002')
ON DUPLICATE KEY UPDATE customer_name = VALUES(customer_name);

INSERT INTO bike_agent(agent_code, agent_name, contact_name, contact_phone)
VALUES ('AGT-001', 'Global E-Bike', 'Bob', '13900000002'), ('AGT-002', 'Pacific Cycle', 'Carol', '13900000003')
ON DUPLICATE KEY UPDATE agent_name = VALUES(agent_name);

INSERT INTO bike_warehouse(warehouse_code, warehouse_name)
VALUES ('FG-01', '成品仓')
ON DUPLICATE KEY UPDATE warehouse_name = VALUES(warehouse_name);

INSERT INTO bike_warehouse_location(warehouse_id, location_code, location_name)
SELECT id, 'A-01-01', 'A区一号位' FROM bike_warehouse WHERE warehouse_code = 'FG-01'
ON DUPLICATE KEY UPDATE location_name = VALUES(location_name);

INSERT INTO bike_workstation(workstation_code, workstation_name, workstation_type)
VALUES ('WS-ASSEMBLY', '总装工位', 'ASSEMBLY'), ('WS-STOCK-IN', '仓库入库', 'STOCK_IN'), ('WS-STOCK-OUT', '仓库出库', 'STOCK_OUT'), ('WS-REWORK', '返工工位', 'REWORK')
ON DUPLICATE KEY UPDATE workstation_name = VALUES(workstation_name);

INSERT INTO bike_sys_role(role_code, role_name, enabled)
VALUES ('SUPER_ADMIN', '超级管理员', 1), ('WEB_OPERATOR', 'Web业务员', 1), ('PDA_OPERATOR', 'PDA操作员', 1)
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), enabled = VALUES(enabled);

INSERT INTO bike_sys_user(username, password_hash, display_name, user_type, enabled, must_change_password)
VALUES ('admin', '$2a$10$7EqJtq98hPqEX7fNZaFWoO9n5fY2X4j7M7qK0X6L2Jf4rXn6m5k7e', '系统管理员', 'WEB_ADMIN', 1, 1)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), enabled = VALUES(enabled);

INSERT INTO bike_vehicle(frame_sn, model_id, status, vehicle_qr_code, vehicle_qr_token, qr_version, qr_status, assemble_time, stock_in_time, current_warehouse_id, current_location_id)
SELECT 'FRAME202609001', id, 2, 'BIKE:1001:DEVQR001', 'DEVQR001', 1, 'ACTIVE', NOW(3) - INTERVAL 2 DAY, NOW(3) - INTERVAL 1 DAY, (SELECT id FROM bike_warehouse WHERE warehouse_code='FG-01'), (SELECT id FROM bike_warehouse_location WHERE location_code='A-01-01') FROM bike_product_model WHERE model_code='CITY-A'
ON DUPLICATE KEY UPDATE status=VALUES(status), vehicle_qr_code=VALUES(vehicle_qr_code), vehicle_qr_token=VALUES(vehicle_qr_token), current_warehouse_id=VALUES(current_warehouse_id), current_location_id=VALUES(current_location_id);
INSERT INTO bike_vehicle(frame_sn, model_id, status, vehicle_qr_code, vehicle_qr_token, qr_version, qr_status, assemble_time)
SELECT 'FRAME202609002', id, 1, 'BIKE:1002:DEVQR002', 'DEVQR002', 1, 'ACTIVE', NOW(3) - INTERVAL 1 DAY FROM bike_product_model WHERE model_code='MTB-B'
ON DUPLICATE KEY UPDATE status=VALUES(status), vehicle_qr_code=VALUES(vehicle_qr_code), vehicle_qr_token=VALUES(vehicle_qr_token);
INSERT INTO bike_vehicle(frame_sn, model_id, status, vehicle_qr_code, vehicle_qr_token, qr_version, qr_status)
SELECT 'FRAME202609003', id, 0, 'BIKE:1003:DEVQR003', 'DEVQR003', 1, 'ACTIVE' FROM bike_product_model WHERE model_code='FOLD-C'
ON DUPLICATE KEY UPDATE status=VALUES(status), vehicle_qr_code=VALUES(vehicle_qr_code), vehicle_qr_token=VALUES(vehicle_qr_token);
INSERT INTO bike_vehicle(frame_sn, model_id, status, vehicle_qr_code, vehicle_qr_token, qr_version, qr_status, assemble_time, stock_in_time, stock_out_time, current_customer_id, current_order_no)
SELECT 'FRAME202609004', id, 3, 'BIKE:1004:DEVQR004', 'DEVQR004', 1, 'ACTIVE', NOW(3) - INTERVAL 3 DAY, NOW(3) - INTERVAL 2 DAY, NOW(3) - INTERVAL 1 DAY, (SELECT id FROM bike_customer WHERE customer_code='CUS-002'), 'SO202609001' FROM bike_product_model WHERE model_code='LONG-D'
ON DUPLICATE KEY UPDATE status=VALUES(status), vehicle_qr_code=VALUES(vehicle_qr_code), vehicle_qr_token=VALUES(vehicle_qr_token), current_customer_id=VALUES(current_customer_id), current_order_no=VALUES(current_order_no);
INSERT INTO bike_vehicle(frame_sn, model_id, status, vehicle_qr_code, vehicle_qr_token, qr_version, qr_status, assemble_time, stock_in_time, current_warehouse_id, current_location_id)
SELECT 'FRAME202609005', id, 2, 'BIKE:1005:DEVQR005', 'DEVQR005', 1, 'ACTIVE', NOW(3) - INTERVAL 4 DAY, NOW(3) - INTERVAL 3 DAY, (SELECT id FROM bike_warehouse WHERE warehouse_code='FG-01'), (SELECT id FROM bike_warehouse_location WHERE location_code='A-01-01') FROM bike_product_model WHERE model_code='CITY-A'
ON DUPLICATE KEY UPDATE status=VALUES(status), vehicle_qr_code=VALUES(vehicle_qr_code), vehicle_qr_token=VALUES(vehicle_qr_token), current_warehouse_id=VALUES(current_warehouse_id), current_location_id=VALUES(current_location_id);

INSERT INTO bike_part(part_sn, part_type, supplier_id, batch_no, qc_status, bind_status, current_vehicle_id, current_frame_sn, status, inbound_time)
SELECT 'MOTOR202609001', 'MOTOR', s.id, 'BATCH-202609', 'PASSED', 'BOUND', v.id, v.frame_sn, 'IN_USE', NOW(3)-INTERVAL 3 DAY FROM bike_supplier s JOIN bike_vehicle v ON v.frame_sn='FRAME202609001'
ON DUPLICATE KEY UPDATE status=VALUES(status), bind_status=VALUES(bind_status), current_vehicle_id=VALUES(current_vehicle_id), current_frame_sn=VALUES(current_frame_sn);
INSERT INTO bike_part(part_sn, part_type, supplier_id, batch_no, qc_status, bind_status, current_vehicle_id, current_frame_sn, status, inbound_time)
SELECT 'BAT202609001', 'BATTERY', s.id, 'BATCH-202609', 'PASSED', 'BOUND', v.id, v.frame_sn, 'IN_USE', NOW(3)-INTERVAL 3 DAY FROM bike_supplier s JOIN bike_vehicle v ON v.frame_sn='FRAME202609001'
ON DUPLICATE KEY UPDATE status=VALUES(status), bind_status=VALUES(bind_status), current_vehicle_id=VALUES(current_vehicle_id), current_frame_sn=VALUES(current_frame_sn);
INSERT INTO bike_part(part_sn, part_type, supplier_id, batch_no, qc_status, bind_status, current_vehicle_id, current_frame_sn, status, inbound_time)
SELECT 'DISPLAY202609001', 'DISPLAY', s.id, 'BATCH-202609', 'PASSED', 'BOUND', v.id, v.frame_sn, 'IN_USE', NOW(3)-INTERVAL 3 DAY FROM bike_supplier s JOIN bike_vehicle v ON v.frame_sn='FRAME202609001'
ON DUPLICATE KEY UPDATE status=VALUES(status), bind_status=VALUES(bind_status), current_vehicle_id=VALUES(current_vehicle_id), current_frame_sn=VALUES(current_frame_sn);
INSERT INTO bike_part(part_sn, part_type, supplier_id, batch_no, qc_status, bind_status, current_vehicle_id, current_frame_sn, status, inbound_time)
SELECT 'MOTOR202609002', 'MOTOR', s.id, 'BATCH-202609', 'PASSED', 'BOUND', v.id, v.frame_sn, 'IN_USE', NOW(3)-INTERVAL 2 DAY FROM bike_supplier s JOIN bike_vehicle v ON v.frame_sn='FRAME202609002'
ON DUPLICATE KEY UPDATE status=VALUES(status), bind_status=VALUES(bind_status), current_vehicle_id=VALUES(current_vehicle_id), current_frame_sn=VALUES(current_frame_sn);
INSERT INTO bike_part(part_sn, part_type, supplier_id, batch_no, qc_status, bind_status, status, inbound_time)
SELECT 'MOTOR202609003', 'MOTOR', s.id, 'BATCH-202609', 'PASSED', 'UNBOUND', 'AVAILABLE', NOW(3)-INTERVAL 1 DAY FROM bike_supplier s
ON DUPLICATE KEY UPDATE status='AVAILABLE', bind_status='UNBOUND';

INSERT INTO bike_vehicle_part_binding(vehicle_id, frame_sn, part_id, part_sn, part_type, bind_time, is_current)
SELECT v.id,v.frame_sn,p.id,p.part_sn,p.part_type,p.inbound_time,1 FROM bike_vehicle v JOIN bike_part p ON p.current_vehicle_id=v.id WHERE v.frame_sn='FRAME202609001'
ON DUPLICATE KEY UPDATE is_current=1;
INSERT INTO bike_vehicle_part_binding(vehicle_id, frame_sn, part_id, part_sn, part_type, bind_time, is_current)
SELECT v.id,v.frame_sn,p.id,p.part_sn,p.part_type,p.inbound_time,1 FROM bike_vehicle v JOIN bike_part p ON p.current_vehicle_id=v.id WHERE v.frame_sn='FRAME202609002'
ON DUPLICATE KEY UPDATE is_current=1;

INSERT INTO bike_sales_order(order_no, customer_id, agent_id, planned_ship_date, status)
SELECT 'SO202609001', c.id, a.id, CURRENT_DATE()+INTERVAL 2 DAY, 'PARTIAL_SHIPPED' FROM bike_customer c JOIN bike_agent a ON a.agent_code='AGT-001' WHERE c.customer_code='CUS-002'
ON DUPLICATE KEY UPDATE status=VALUES(status), customer_id=VALUES(customer_id), agent_id=VALUES(agent_id);
INSERT INTO bike_sales_order_item(order_id, product_model_id, ordered_qty, shipped_qty)
SELECT o.id,m.id,2,1 FROM bike_sales_order o JOIN bike_product_model m ON m.model_code='LONG-D' WHERE o.order_no='SO202609001'
ON DUPLICATE KEY UPDATE ordered_qty=VALUES(ordered_qty), shipped_qty=VALUES(shipped_qty);
INSERT INTO bike_stock_in_record(stock_in_no,vehicle_id,warehouse_id,location_id,status,operation_time)
SELECT 'IN20260905001',v.id,w.id,l.id,'COMPLETED',NOW(3)-INTERVAL 1 DAY FROM bike_vehicle v JOIN bike_warehouse w ON w.warehouse_code='FG-01' JOIN bike_warehouse_location l ON l.location_code='A-01-01' WHERE v.frame_sn='FRAME202609001'
ON DUPLICATE KEY UPDATE status='COMPLETED';
INSERT INTO bike_stock_in_record(stock_in_no,vehicle_id,warehouse_id,location_id,status,operation_time)
SELECT 'IN20260905002',v.id,w.id,l.id,'COMPLETED',NOW(3)-INTERVAL 3 DAY FROM bike_vehicle v JOIN bike_warehouse w ON w.warehouse_code='FG-01' JOIN bike_warehouse_location l ON l.location_code='A-01-01' WHERE v.frame_sn='FRAME202609005'
ON DUPLICATE KEY UPDATE status='COMPLETED';
INSERT INTO bike_stock_out_record(stock_out_no,vehicle_id,customer_id,order_id,status,operation_time)
SELECT 'OUT20260905001',v.id,c.id,o.id,'COMPLETED',NOW(3)-INTERVAL 1 DAY FROM bike_vehicle v JOIN bike_customer c ON c.customer_code='CUS-002' JOIN bike_sales_order o ON o.order_no='SO202609001' WHERE v.frame_sn='FRAME202609004'
ON DUPLICATE KEY UPDATE status='COMPLETED';
INSERT INTO bike_sales_order_vehicle(order_id,vehicle_id,stock_out_record_id)
SELECT o.id,v.id,s.id FROM bike_sales_order o JOIN bike_vehicle v ON v.frame_sn='FRAME202609004' JOIN bike_stock_out_record s ON s.stock_out_no='OUT20260905001'
ON DUPLICATE KEY UPDATE stock_out_record_id=VALUES(stock_out_record_id);
INSERT INTO bike_inventory_lock(lock_no,agent_id,product_model_id,lock_qty,expire_time,created_by,status,remark)
SELECT 'LK20260905001',a.id,m.id,1,NOW(3)+INTERVAL 48 HOUR,0,'LOCKED','开发环境样例锁定' FROM bike_agent a JOIN bike_product_model m ON m.model_code='CITY-A' WHERE a.agent_code='AGT-002'
ON DUPLICATE KEY UPDATE status='LOCKED', expire_time=VALUES(expire_time);
INSERT INTO bike_operation_log(business_type,business_id,action,frame_sn,operator_name,operation_time,remark)
VALUES ('ASSEMBLE','1','四码绑定','FRAME202609001','admin',NOW(3)-INTERVAL 2 DAY,'开发样例'),('STOCK_IN','IN20260905001','整车入库','FRAME202609001','admin',NOW(3)-INTERVAL 1 DAY,'开发样例'),('STOCK_OUT','OUT20260905001','批量出库','FRAME202609004','admin',NOW(3)-INTERVAL 1 DAY,'开发样例'),('INVENTORY_LOCK','LK20260905001','库存锁定',NULL,'admin',NOW(3),'开发样例');
