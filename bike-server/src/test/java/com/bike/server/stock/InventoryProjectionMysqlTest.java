package com.bike.server.stock;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.*;
import java.util.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class InventoryProjectionMysqlTest {
    @Test void preservesUnsupportedCommitmentsAndNeverNetsDifferentModels() throws Exception {
        var source=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",System.getenv("BIKE_MYSQL_TEST_URL"),System.getenv("BIKE_MYSQL_TEST_USER"),System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
        var config=new Configuration(new Environment("projection",new JdbcTransactionFactory(),source));
        config.setMapUnderscoreToCamelCase(true);
        String tablePrefix="accept_ip_"+UUID.randomUUID().toString().replace("-","").substring(0,16)+"_";
        var createdTables=new ArrayList<String>();
        try(var stream=Resources.getResourceAsStream("mapper/InventoryPolicyMapper.xml")) {
            String mapper=new String(stream.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",tablePrefix);
            new XMLMapperBuilder(new ByteArrayInputStream(mapper.getBytes(StandardCharsets.UTF_8)),config,"inventory-policy",config.getSqlFragments()).parse();
        }
        for(String file:List.of("OrderMapper","OrderReservationMapper"))try(var stream=Resources.getResourceAsStream("mapper/"+file+".xml")) {
            String mapper=new String(stream.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",tablePrefix);
            new XMLMapperBuilder(new ByteArrayInputStream(mapper.getBytes(StandardCharsets.UTF_8)),config,file,config.getSqlFragments()).parse();
        }
        String xml="""
          <?xml version="1.0" encoding="UTF-8"?>
          <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
          <mapper namespace="projection">
            <select id="rows" resultType="map"><include refid="com.bike.server.stock.InventoryPolicyMapper.inventoryCtes"/> SELECT * FROM inventory_projection ORDER BY model_id,warehouse_id</select>
            <select id="summary" resultType="map"><include refid="com.bike.server.stock.InventoryPolicyMapper.inventorySummary"/></select>
          </mapper>
          """;
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),config,"projection-test",config.getSqlFragments()).parse();
        try(var session=new SqlSessionFactoryBuilder().build(config).openSession();var sql=session.getConnection().createStatement()) {
            // 唯一前缀隔离表；MySQL临时表不支持CTE中重复引用，不能用其替代真实表。
            for(String ddl:List.of(
                "bike_product_model(id BIGINT,model_name VARCHAR(30))",
                "bike_warehouse(id BIGINT,warehouse_name VARCHAR(30))",
                "bike_warehouse_location(id BIGINT,location_name VARCHAR(30))",
                "bike_vehicle(id BIGINT,model_id BIGINT,status INT,frame_sn VARCHAR(30),current_warehouse_id BIGINT,current_location_id BIGINT,stock_in_time DATETIME)",
                "bike_production_issue(code VARCHAR(30),status VARCHAR(20))",
                "bike_vehicle_part_binding(vehicle_id BIGINT,part_sn VARCHAR(30),is_current INT)",
                "bike_inventory_lock(product_model_id BIGINT,lock_qty INT,released_qty INT,converted_qty INT,status VARCHAR(30),expire_time DATETIME,sku_id BIGINT,scope_type VARCHAR(16) DEFAULT 'MODEL_LEGACY')",
                "bike_order_reservation(order_id BIGINT,model_id BIGINT,reserved_qty INT,expires_at DATETIME,hold_mode VARCHAR(16) DEFAULT 'TIMED',scope_type VARCHAR(16) DEFAULT 'MODEL_LEGACY')",
                "bike_order_reservation_item(order_id BIGINT,sku_id BIGINT,ordered_qty INT,shipped_qty INT,closed_qty INT)",
                "bike_production_vehicle(vehicle_id BIGINT,sku_id BIGINT)",
                "bike_sales_order(id BIGINT,status VARCHAR(30))",
                "bike_sales_order_item(order_id BIGINT,product_model_id BIGINT,shipped_qty INT)")) {
                String isolated=ddl.replace("bike_",tablePrefix);
                sql.execute("CREATE TABLE "+isolated);createdTables.add(isolated.substring(0,isolated.indexOf('(')));
            }
            for(String insert:List.of(
                "INSERT INTO bike_product_model VALUES(1,'A'),(2,'B'),(3,'C')",
                "INSERT INTO bike_warehouse VALUES(1,'W1'),(2,'W2')",
                "INSERT INTO bike_warehouse_location VALUES(1,'L1'),(2,'L2')",
                "INSERT INTO bike_vehicle VALUES(1,1,2,'F1',1,1,NOW()),(2,1,2,'F2',1,1,NOW()),(3,1,2,'F3',2,2,NOW()),(4,2,2,'F4',1,1,NOW()),(5,2,2,'F5',1,1,NOW())",
                "INSERT INTO bike_production_issue VALUES('F1','OPEN')",
                "INSERT INTO bike_inventory_lock(product_model_id,lock_qty,released_qty,converted_qty,status,expire_time) VALUES(1,2,0,0,'LOCKED',DATE_ADD(NOW(),INTERVAL 1 DAY)),(3,3,0,0,'LOCKED',DATE_ADD(NOW(),INTERVAL 1 DAY))",
                "INSERT INTO bike_sales_order VALUES(1,'PARTIAL_SHIPPED')",
                "INSERT INTO bike_sales_order_item VALUES(1,1,1)",
                "INSERT INTO bike_order_reservation(order_id,model_id,reserved_qty,expires_at) VALUES(1,1,3,DATE_ADD(NOW(),INTERVAL 1 DAY))"))sql.execute(insert.replace("bike_",tablePrefix));
            List<Map<String,Object>> rows=session.selectList("projection.rows");
            assertThat(rows).hasSize(4);
            var unsupported=rows.stream().filter(r->n(r,"model_id")==3).findFirst().orElseThrow();
            assertThat(n(unsupported,"physical_qty")).isZero();assertThat(n(unsupported,"locked_qty")).isEqualTo(3);assertThat(n(unsupported,"shortage_qty")).isEqualTo(3);
            for(var row:rows){
                assertThat(n(row,"locked_qty")).isEqualTo(n(row,"temporary_locked_qty")+n(row,"order_reserved_qty"));
                assertThat(n(row,"physical_qty")-n(row,"quarantined_qty")-n(row,"locked_qty")).isEqualTo(n(row,"available_qty")-n(row,"shortage_qty"));
            }
            Map<String,Object> summary=session.selectOne("projection.summary");
            assertThat(n(summary,"physicalQty")).isEqualTo(5);assertThat(n(summary,"quarantinedQty")).isEqualTo(1);
            assertThat(n(summary,"temporaryLockedQty")).isEqualTo(5);assertThat(n(summary,"orderReservedQty")).isEqualTo(2);
            assertThat(n(summary,"availableQty")).isEqualTo(2);assertThat(n(summary,"shortageQty")).isEqualTo(5);
            assertThat(n(summary,"skuShortageQty")).isZero();assertThat(n(summary,"legacyModelCommittedQty")).isEqualTo(7);
            assertThat(n(summary,"modelNewCommitmentQty")).isEqualTo(2);
            sql.execute("SET timestamp=1900000000");
            sql.execute("UPDATE "+tablePrefix+"inventory_lock SET expire_time=DATE_ADD(NOW(),INTERVAL 1 DAY)");
            sql.execute("UPDATE "+tablePrefix+"order_reservation SET expires_at=DATE_ADD(NOW(),INTERVAL 1 SECOND)");
            var parameters=Map.of("modelId",1L,"orderId",1L);
            com.bike.server.order.OrderInventorySnapshot before=session.selectOne("com.bike.server.order.OrderReservationMapper.snapshot",parameters);
            assertThat(before.getOwnedQty()).isEqualTo(2);assertThat(before.getCommittedQty()).isEqualTo(4);
            sql.execute("SET timestamp=1900000001");session.clearCache();
            com.bike.server.order.OrderInventorySnapshot boundary=session.selectOne("com.bike.server.order.OrderReservationMapper.snapshot",parameters);
            assertThat(boundary.getOwnedQty()).isZero();assertThat(boundary.getCommittedQty()).isEqualTo(2);
            assertThat(boundary.getReservationCount()).isEqualTo(1);
            assertThat((Integer)session.selectOne("com.bike.server.order.OrderReservationMapper.expiredForOrder",parameters)).isEqualTo(1);
        } finally {
            try(var connection=source.getConnection();var sql=connection.createStatement()) {
                for(String table:createdTables){assertThat(table).startsWith(tablePrefix).matches("[a-z0-9_]+");sql.execute("DROP TABLE "+table);}
            }
        }
    }
    static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
}
