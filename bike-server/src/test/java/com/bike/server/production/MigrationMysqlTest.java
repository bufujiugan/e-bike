package com.bike.server.production;

import static org.assertj.core.api.Assertions.*;
import com.bike.server.production.migration.V16__production_workflow;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 仅针对两个新建的专用空库，禁止清库或重复覆盖已有业务数据。 */
@EnabledIfEnvironmentVariable(named="BIKE_MIGRATION_CLEAN_URL",matches=".+")
class MigrationMysqlTest {
  private static final int LATEST_VERSION = 39;
  Flyway flyway(String url,String target) {
    assertThat(url).contains("/bike_migration_");
    var config=Flyway.configure().dataSource(url,System.getenv("BIKE_MYSQL_TEST_USER"),System.getenv("BIKE_MYSQL_TEST_PASSWORD"))
        .locations("classpath:db/migration-prod").table("bike_schema_history").javaMigrations(new V16__production_workflow()).cleanDisabled(true);
    if(target!=null)config.target(target);
    return config.load();
  }
  Connection connection(String url)throws Exception{return DriverManager.getConnection(url,System.getenv("BIKE_MYSQL_TEST_USER"),System.getenv("BIKE_MYSQL_TEST_PASSWORD"));}
  @Test void emptyProductionDatabaseMigratesWithoutBusinessSeed() throws Exception {
    String url=System.getenv("BIKE_MIGRATION_CLEAN_URL");
    Flyway flyway=flyway(url,null);
    assertThat(flyway.info().applied()).isEmpty();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(LATEST_VERSION);
    flyway.validate();assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(Integer.toString(LATEST_VERSION));
    try(var conn=connection(url);var sql=conn.createStatement()) {
      try(var result=sql.executeQuery("SELECT (SELECT COUNT(*) FROM bike_vehicle)+(SELECT COUNT(*) FROM bike_part)+(SELECT COUNT(*) FROM bike_sales_order)")){result.next();assertThat(result.getInt(1)).isZero();}
      try(var result=sql.executeQuery("SELECT must_change_password,auth_version FROM bike_sys_user WHERE username='admin'")){assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isEqualTo(1);assertThat(result.getLong(2)).isZero();}
      try(var result=sql.executeQuery("SELECT COUNT(*) FROM bike_production_scan_failure")){result.next();assertThat(result.getInt(1)).isZero();}
      try(var result=sql.executeQuery("SELECT COUNT(*) FROM bike_agent_model_access")){result.next();assertThat(result.getInt(1)).isZero();}
      verifyManagementPermission(sql); verifyAfterSalesAndPiSchema(sql);
    }
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }
  @Test void upgradeFromV16PreservesExistingParts() throws Exception {
    String url=System.getenv("BIKE_MIGRATION_UPGRADE_URL");
    Flyway previous=flyway(url,"16");assertThat(previous.info().applied()).isEmpty();
    assertThat(previous.migrate().migrationsExecuted).isEqualTo(16);
    try(var conn=connection(url);var sql=conn.createStatement()) {
      sql.executeUpdate("INSERT INTO bike_part(part_sn,part_type,qc_status,remark) VALUES('MIGRATION-PRESERVE-001','MOTOR','PASSED','升级保留验收')");
    }
    Flyway v24=flyway(url,"24");assertThat(v24.migrate().migrationsExecuted).isEqualTo(8);v24.validate();
    try(var conn=connection(url);var sql=conn.createStatement()) { insertExpiredPaidReservation(sql); insertLegacyAfterSales(sql); }
    Flyway next=flyway(url,null);assertThat(next.migrate().migrationsExecuted).isEqualTo(LATEST_VERSION - 24);next.validate();
    try(var conn=connection(url);var sql=conn.createStatement();var result=sql.executeQuery("SELECT remark,qc_status FROM bike_part WHERE part_sn='MIGRATION-PRESERVE-001'")) {
      assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo("升级保留验收");assertThat(result.getString(2)).isEqualTo("PASSED");
    }
    assertThat(next.migrate().migrationsExecuted).isZero();
    try(var conn=connection(url);var sql=conn.createStatement()) {
      verifyManagementPermission(sql); verifyAfterSalesAndPiSchema(sql);
      verifyLegacyAfterSales(sql);
      try(var result=sql.executeQuery("SELECT hold_mode,expires_at,payment_hold_pi_id,payment_held_at,payment_held_by,payment_held_amount FROM bike_order_reservation WHERE order_id=9001")) {
        assertThat(result.next()).isTrue();assertThat(result.getString("hold_mode")).isEqualTo("TIMED");
        assertThat(result.getTimestamp("expires_at").toLocalDateTime()).isEqualTo(java.time.LocalDateTime.of(2020,1,1,0,0));
        for(String field:new String[]{"payment_hold_pi_id","payment_held_at","payment_held_by","payment_held_amount"})assertThat(result.getObject(field)).as(field).isNull();
      }
      try(var result=sql.executeQuery("SELECT amount FROM bike_pi_payment WHERE pi_id=9001")) {assertThat(result.next()).isTrue();assertThat(result.getBigDecimal(1)).isEqualByComparingTo("100.00");}
      try(var result=sql.executeQuery("SELECT p.id,p.revision,s.current_pi_id,s.next_revision,l.order_id,l.link_type FROM bike_pi p JOIN bike_pi_series s ON s.id=p.series_id JOIN bike_pi_order_link l ON l.series_id=s.id WHERE p.id=9001")) {
        assertThat(result.next()).isTrue();assertThat(result.getLong("current_pi_id")).isEqualTo(9001);assertThat(result.getInt("revision")).isEqualTo(1);
        assertThat(result.getInt("next_revision")).isEqualTo(2);assertThat(result.getLong("order_id")).isEqualTo(9001);assertThat(result.getString("link_type")).isEqualTo("LEGACY_SOURCE");assertThat(result.next()).isFalse();
      }
      try(var result=sql.executeQuery("SELECT b.source_type,b.execution_pi_id,b.created_at,i.sku_id,so.stock_out_no,so.status FROM bike_stock_out_batch b JOIN bike_stock_out_batch_item i ON i.batch_id=b.id JOIN bike_stock_out_record so ON so.id=i.stock_out_id WHERE so.id IN (9001,9002) ORDER BY so.id")) {
        for(int n=0;n<2;n++){assertThat(result.next()).isTrue();assertThat(result.getString("source_type")).isEqualTo("LEGACY_SINGLE");assertThat(result.getObject("execution_pi_id")).isNull();assertThat(result.getObject("sku_id")).isNull();assertThat(result.getString("stock_out_no")).hasSize(64);assertThat(result.getString("status")).isEqualTo("CANCELLED");assertThat(result.getTimestamp("created_at").toLocalDateTime()).isEqualTo(java.time.LocalDateTime.of(2019,12,30,12,0));}assertThat(result.next()).isFalse();
      }
      try(var result=sql.executeQuery("SELECT COUNT(DISTINCT batch_id) FROM bike_stock_out_batch_item WHERE stock_out_id IN (9001,9002)")){result.next();assertThat(result.getInt(1)).isEqualTo(2);}
      try(var result=sql.executeQuery("SELECT stock_out_batch_id,link_type,status,tracking_no,revision FROM bike_pi_logistics WHERE id=9001")){assertThat(result.next()).isTrue();assertThat(result.getObject(1)).isNull();assertThat(result.getString(2)).isEqualTo("LEGACY_UNLINKED");assertThat(result.getString(3)).isEqualTo("SHIPPED");assertThat(result.getString(4)).isEqualTo("OLD-TRACK");assertThat(result.getInt(5)).isEqualTo(1);}
      try(var result=sql.executeQuery("SELECT action,JSON_UNQUOTE(JSON_EXTRACT(snapshot,'$.trackingNo')) FROM bike_pi_logistics_history WHERE logistics_id=9001")){assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo("MIGRATED_CURRENT");assertThat(result.getString(2)).isEqualTo("OLD-TRACK");assertThat(result.next()).isFalse();}
    }
  }
  private void insertExpiredPaidReservation(Statement sql)throws Exception {
    sql.executeUpdate("INSERT INTO bike_customer(id,customer_code,customer_name) VALUES(9001,'MIGRATE-CUSTOMER','迁移保留客户')");
    sql.executeUpdate("INSERT INTO bike_agent(id,agent_code,agent_name) VALUES(9001,'MIGRATE-AGENT','迁移保留代理商')");
    sql.executeUpdate("INSERT INTO bike_product_model(id,model_code,model_name) VALUES(9001,'MIGRATE-MODEL','迁移保留车型')");
    sql.executeUpdate("INSERT INTO bike_sales_order(id,order_no,customer_id,agent_id,status) VALUES(9001,'MIGRATE-ORDER',9001,9001,'CONFIRMED')");
    sql.executeUpdate("INSERT INTO bike_sales_order_item(order_id,product_model_id,ordered_qty) VALUES(9001,9001,2)");
    sql.executeUpdate("INSERT INTO bike_inventory_lock(id,lock_no,agent_id,product_model_id,lock_qty,converted_qty,status,expire_time,created_by) VALUES(9001,'MIGRATE-LOCK',9001,9001,2,2,'CONVERTED','2020-01-01 00:00:00',1)");
    sql.executeUpdate("INSERT INTO bike_order_reservation(order_id,source_lock_id,request_id,model_id,reserved_qty,expires_at) VALUES(9001,9001,'migration-expired-paid',9001,2,'2020-01-01 00:00:00')");
    sql.executeUpdate("INSERT INTO bike_pi(id,pi_no,customer_id,customer_code,customer_name,agent_id,source_order_id,status,payment_status,total_amount,deposit_required_amount,shipment_required_amount) VALUES(9001,'MIGRATE-PI',9001,'MIGRATE-CUSTOMER','迁移保留客户',9001,9001,'CONFIRMED','PAID',100,25,100)");
    sql.executeUpdate("INSERT INTO bike_pi_payment(pi_id,request_id,amount,currency_code,received_at) VALUES(9001,'migration-paid-before-upgrade',100,'CNY','2019-12-31 00:00:00')");
    sql.executeUpdate("INSERT INTO bike_pi_logistics(id,pi_id,batch_no,carrier,tracking_no,status,shipped_at) VALUES(9001,9001,'OLD-MANUAL','OLD-CARRIER','OLD-TRACK','SHIPPED','2019-12-30 12:00:00')");
    sql.executeUpdate("INSERT INTO bike_vehicle(id,frame_sn,model_id,status) VALUES(9001,'MIGRATE-FRAME',9001,2)");
    sql.executeUpdate("INSERT INTO bike_stock_out_record(id,stock_out_no,vehicle_id,customer_id,order_id,status,operation_time) VALUES(9001,REPEAT('X',64),9001,9001,9001,'CANCELLED','2019-12-30 12:00:00'),(9002,REPEAT('Y',64),9001,9001,9001,'CANCELLED','2019-12-30 12:00:00')");
  }
  private void insertLegacyAfterSales(Statement sql)throws Exception {
    sql.executeUpdate("UPDATE bike_agent SET contact_name='迁移联系人',contact_phone='migration-phone' WHERE id=9001");
    sql.executeUpdate("INSERT INTO bike_after_sales_case(id,case_no,agent_id,description,assignee_id) VALUES(9001,'MIGRATE-CASE',9001,'历史案例快照验证',1)");
    sql.executeUpdate("UPDATE bike_after_sales_case SET technical_judgment='旧技术判断',solution='旧维修方案',internal_note='历史内部备注' WHERE id=9001");
    sql.executeUpdate("INSERT INTO bike_after_sales_shipment(id,case_id,part_name,quantity,old_part_return_required,old_part_return_received_at) VALUES(9001,9001,'待退回电机',1,1,NULL),(9002,9001,'已收到电池',2,1,'2025-03-01 12:00:00'),(9003,9001,'无需退回仪表',1,0,NULL)");
    sql.executeUpdate("INSERT INTO bike_sys_role(role_code,role_name,enabled) VALUES('MIGRATE_READ_ONLY','迁移只读角色',1),('MIGRATE_TECH','迁移技术角色',1)");
    sql.executeUpdate("INSERT INTO bike_sys_role_permission(role_id,permission_id) SELECT r.id,p.id FROM bike_sys_role r JOIN bike_sys_permission p ON (r.role_code='MIGRATE_READ_ONLY' AND p.permission_code='AFTER_SALES_READ') OR (r.role_code='MIGRATE_TECH' AND p.permission_code='AFTER_SALES_TECH')");
  }
  private void verifyLegacyAfterSales(Statement sql)throws Exception {
    try(var result=sql.executeQuery("SELECT warehouse_name,recipient,address_line,logistics_status FROM bike_after_sales_shipment WHERE id=9001")){assertThat(result.next()).isTrue();for(int i=1;i<=4;i++)assertThat(result.getObject(i)).isNull();}
    try(var result=sql.executeQuery("SELECT status,JSON_UNQUOTE(JSON_EXTRACT(document_json,'$.solution')),JSON_UNQUOTE(JSON_EXTRACT(document_json,'$.internalNote')),JSON_LENGTH(JSON_EXTRACT(document_json,'$.parts')),confirmed_at,approved_at,created_by FROM bike_after_sales_solution_version WHERE case_id=9001")) {
      assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo("LEGACY");assertThat(result.getString(2)).isEqualTo("旧维修方案");assertThat(result.getString(3)).isEqualTo("历史内部备注");assertThat(result.getInt(4)).isZero();for(int i=5;i<=7;i++)assertThat(result.getObject(i)).isNull();assertThat(result.next()).isFalse();
    }
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM bike_after_sales_shipment WHERE case_id=9001 AND approval_source='LEGACY_UNLINKED' AND solution_item_id IS NULL")){result.next();assertThat(result.getInt(1)).isEqualTo(3);}
    try(var result=sql.executeQuery("SELECT shipment_id,status,received_at,warehouse_name,tracking_no,updated_by FROM bike_after_sales_return ORDER BY shipment_id")) {
      assertThat(result.next()).isTrue();assertThat(result.getLong(1)).isEqualTo(9001);assertThat(result.getString(2)).isEqualTo("REQUIRED");assertThat(result.getObject(3)).isNull();
      for(int n=4;n<=6;n++)assertThat(result.getObject(n)).isNull();
      assertThat(result.next()).isTrue();assertThat(result.getLong(1)).isEqualTo(9002);assertThat(result.getString(2)).isEqualTo("RECEIVED");assertThat(result.getTimestamp(3).toLocalDateTime()).isEqualTo(java.time.LocalDateTime.of(2025,3,1,12,0));
      for(int n=4;n<=6;n++)assertThat(result.getObject(n)).isNull();assertThat(result.next()).isFalse();
    }
    try(var result=sql.executeQuery("SELECT company_name,contact_name,contact_phone,contact_email,snapshot_source,assignee_name FROM bike_after_sales_case_contact WHERE case_id=9001")) {
      assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo("迁移保留代理商");
      assertThat(result.getString(2)).isEqualTo("迁移联系人");assertThat(result.getString(3)).isEqualTo("migration-phone");
      assertThat(result.getString(4)).isNull();assertThat(result.getString(5)).isEqualTo("MIGRATED_CURRENT");assertThat(result.getString(6)).contains("admin");
    }
    for(String role:new String[]{"MIGRATE_READ_ONLY","MIGRATE_TECH"}) {
      try(var result=sql.executeQuery("SELECT COUNT(*) FROM bike_sys_role_permission rp JOIN bike_sys_role r ON r.id=rp.role_id JOIN bike_sys_permission p ON p.id=rp.permission_id WHERE r.role_code='"+role+"' AND p.permission_code='AFTER_SALES_SCOPE_ALL'")) {
        result.next();assertThat(result.getInt(1)).as(role).isEqualTo(role.equals("MIGRATE_TECH")?1:0);
      }
    }
    try(var result=sql.executeQuery("SELECT scope_type,sku_id,customer_id FROM bike_inventory_lock WHERE id=9001")) {
      assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo("MODEL_LEGACY");assertThat(result.getObject(2)).isNull();assertThat(result.getObject(3)).isNull();
    }
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM bike_order_reservation_item WHERE order_id=9001")){result.next();assertThat(result.getInt(1)).isZero();}
  }
  private void verifyManagementPermission(Statement sql)throws Exception {
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM bike_sys_permission WHERE permission_code='PRODUCTION_MANAGE'")){result.next();assertThat(result.getInt(1)).isEqualTo(1);}
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM bike_sys_role_permission rp JOIN bike_sys_permission p ON p.id=rp.permission_id WHERE p.permission_code='PRODUCTION_MANAGE'")){result.next();assertThat(result.getInt(1)).isZero();}
  }
  private void verifyAfterSalesAndPiSchema(Statement sql)throws Exception {
    for(String table: new String[]{"bike_pi_template","bike_pi_export"}) {
      try(var result=sql.executeQuery("SELECT COUNT(*) FROM "+table)){result.next();assertThat(result.getInt(1)).as("不伪造历史模板发布或导出："+table).isZero();}
    }
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM bike_sys_role_permission rp JOIN bike_sys_role r ON r.id=rp.role_id JOIN bike_sys_permission p ON p.id=rp.permission_id WHERE p.permission_code='PI_TEMPLATE_MANAGE' AND r.role_code!='SUPER_ADMIN'")){result.next();assertThat(result.getInt(1)).isZero();}
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bike_after_sales_return' AND column_comment!=''")){result.next();assertThat(result.getInt(1)).isEqualTo(20);}
    for(String table:new String[]{"bike_pi_logistics_history","bike_pi_logistics_request","bike_stock_out_batch","bike_stock_out_batch_item","bike_pi_series","bike_pi_order_link","bike_pi_conversion_request","bike_pi_payment_attachment","bike_order_reservation_item","bike_sku_lock_request","bike_after_sales_company_profile","bike_after_sales_sales_agent","bike_after_sales_case_contact"}) {
      try(var result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='"+table+"'")){result.next();assertThat(result.getInt(1)).as(table).isEqualTo(1);}
      try(var result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"+table+"' AND column_comment=''")){result.next();assertThat(result.getInt(1)).as(table+"字段注释").isZero();}
    }
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM bike_sys_permission WHERE permission_code IN ('AFTER_SALES_SCOPE_ALL','AFTER_SALES_COMPANY_MANAGE','AFTER_SALES_ASSIGN')")){result.next();assertThat(result.getInt(1)).isEqualTo(3);}
    for (String table : new String[]{"bike_after_sales_case","bike_after_sales_message","bike_after_sales_attachment","bike_after_sales_shipment","bike_pi","bike_pi_item","bike_pi_payment","bike_pi_logistics","bike_order_reservation"}) {
      try (var result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='"+table+"'")){result.next();assertThat(result.getInt(1)).as(table).isEqualTo(1);}
    }
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM bike_sys_permission WHERE permission_code IN ('AFTER_SALES_READ','PI_READ')")){result.next();assertThat(result.getInt(1)).isEqualTo(2);}
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM bike_sys_role_permission rp JOIN bike_sys_permission p ON p.id=rp.permission_id WHERE p.permission_code IN ('PI_PAYMENT_CONFIRM','PI_RELEASE')")){result.next();assertThat(result.getInt(1)).as("新财务权限不能自动授予普通岗位").isZero();}
    for (String table : new String[]{"bike_after_sales_case","bike_after_sales_message","bike_after_sales_attachment","bike_after_sales_shipment","bike_pi","bike_pi_item","bike_pi_payment","bike_pi_logistics"}) {
      try (var result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"+table+"' AND column_comment=''")){result.next();assertThat(result.getInt(1)).as(table + "字段注释").isZero();}
    }
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bike_pi' AND column_name IN ('document_snapshot','customer_name','customer_code','shipment_required_amount')")){result.next();assertThat(result.getInt(1)).isEqualTo(4);}
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bike_pi_item' AND column_name IN ('sku_id','sku_code','product_name','specification')")){result.next();assertThat(result.getInt(1)).isEqualTo(4);}
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bike_after_sales_attachment' AND column_name IN ('message_id','category')")){result.next();assertThat(result.getInt(1)).isEqualTo(2);}
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bike_order_reservation' AND column_name IN ('hold_mode','payment_hold_pi_id','payment_held_at','payment_held_by','payment_held_amount','released_at') AND column_comment!=''")){result.next();assertThat(result.getInt(1)).isEqualTo(6);}
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bike_pi' AND column_name='previous_revision_id'")){result.next();assertThat(result.getInt(1)).isEqualTo(1);}
    try(var result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='bike_pi_revision_request'")){result.next();assertThat(result.getInt(1)).isEqualTo(1);}
  }
}
