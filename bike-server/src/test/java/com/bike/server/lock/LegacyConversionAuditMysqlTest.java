package com.bike.server.lock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** 只读旧转换对账查询的真实 SQL 验证，使用随机前缀表并在 finally 清理。 */
@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL", matches=".+")
class LegacyConversionAuditMysqlTest {
    @Test void listsOnlyConvertedLocksWithoutRealReservation() throws Exception {
        var source=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",System.getenv("BIKE_MYSQL_TEST_URL"),System.getenv("BIKE_MYSQL_TEST_USER"),System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
        var config=new Configuration(new Environment("legacy-audit",new JdbcTransactionFactory(),source));
        config.setMapUnderscoreToCamelCase(true);
        String prefix="accept_lca_"+UUID.randomUUID().toString().replace("-","").substring(0,14)+"_";
        for(String mapperName: List.of("InventoryPolicyMapper","AgentModelAccessMapper","InventoryLockMapper")) try(var input=Resources.getResourceAsStream("mapper/"+mapperName+".xml")) {
            String xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix);
            new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),config,mapperName,config.getSqlFragments()).parse();
        }
        try(var session=new SqlSessionFactoryBuilder().build(config).openSession();var sql=session.getConnection().createStatement()) {
            List<String> tables=new ArrayList<>();
            try {
                for(String ddl:List.of(
                    "agent(id BIGINT,agent_name VARCHAR(30),status VARCHAR(20))",
                    "product_model(id BIGINT,model_name VARCHAR(30))",
                    "inventory_lock(id BIGINT,lock_no VARCHAR(50),agent_id BIGINT,product_model_id BIGINT,lock_qty INT,released_qty INT,converted_qty INT,status VARCHAR(30),expire_time DATETIME,created_at DATETIME,remark VARCHAR(100))",
                    "order_reservation(order_id BIGINT,source_lock_id BIGINT)")) {
                    String table=prefix+ddl.substring(0,ddl.indexOf('('));
                    sql.execute("CREATE TABLE "+prefix+ddl);tables.add(table);
                }
                sql.execute("INSERT INTO "+prefix+"agent VALUES(1,'代理商','ENABLED')");
                sql.execute("INSERT INTO "+prefix+"product_model VALUES(1,'车型')");
                sql.execute("INSERT INTO "+prefix+"inventory_lock VALUES(1,'OLD-1',1,1,5,0,2,'CONVERTED',DATE_ADD(NOW(),INTERVAL 1 DAY),NOW(),'旧版本转换'),(2,'NEW-1',1,1,3,0,1,'PARTIAL_CONVERTED',DATE_ADD(NOW(),INTERVAL 1 DAY),NOW(),'新版来源')");
                sql.execute("INSERT INTO "+prefix+"order_reservation VALUES(9,2)");
                var mapper=session.getMapper(InventoryLockMapper.class);
                assertThat(mapper.countLegacyConversions()).isEqualTo(1);
                var rows=mapper.selectLegacyConversions(0,20);
                assertThat(rows).hasSize(1);assertThat(rows.get(0).lockNo()).isEqualTo("OLD-1");
                assertThat(rows.get(0).convertedQty()).isEqualTo(2);assertThat(rows.get(0).remark()).isEqualTo("旧版本转换");
            } finally { for(String table:tables){assertThat(table).startsWith(prefix).matches("[a-z0-9_]+");sql.execute("DROP TABLE "+table);} }
        }
    }
}
