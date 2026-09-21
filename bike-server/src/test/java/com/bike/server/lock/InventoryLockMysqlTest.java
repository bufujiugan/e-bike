package com.bike.server.lock;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 在专用 MySQL 连接中使用临时表，执行正式 Mapper SQL，不更改业务数据。 */
@EnabledIfEnvironmentVariable(named = "BIKE_MYSQL_TEST_URL", matches = ".+")
class InventoryLockMysqlTest {
    @Test void partialConversionsKeepRemainingQuantityLocked() throws Exception {
        var dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", System.getenv("BIKE_MYSQL_TEST_URL"),
                System.getenv("BIKE_MYSQL_TEST_USER"), System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
        var configuration = new Configuration(new Environment("mysql-test", new JdbcTransactionFactory(), dataSource));
        for (String resource : new String[]{"mapper/InventoryPolicyMapper.xml", "mapper/AgentModelAccessMapper.xml", "mapper/InventoryLockMapper.xml", "mapper/InventoryLockSqlFixture.xml"}) {
            try (var input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            session.update("lockFixture.createModels");
            session.insert("lockFixture.seedModels");
            var models = session.getMapper(InventoryLockMapper.class);
            assertThat(models.lockModelForUpdate(1)).isEqualTo(1);
            assertThat(models.lockModelForUpdate(27)).isEqualTo(1);
            assertThat(models.lockModelForUpdate(28)).isNull();
            assertThat(models.lockModelForUpdate(999)).isNull();
            session.update("lockFixture.create");
            session.insert("lockFixture.seed");
            var mapper = session.getMapper(InventoryLockMapper.class);
            assertThat(mapper.convert(1, 5)).isEqualTo(1);
            Map<String,Object> partial = session.selectOne("lockFixture.read");
            assertThat(partial.get("status")).isEqualTo("PARTIAL_CONVERTED");
            assertThat(((Number) partial.get("remaining")).intValue()).isEqualTo(5);
            assertThat(mapper.convert(1, 2)).isEqualTo(1);
            partial = session.selectOne("lockFixture.read");
            assertThat(partial.get("status")).isEqualTo("PARTIAL_CONVERTED");
            assertThat(((Number) partial.get("remaining")).intValue()).isEqualTo(3);
            assertThat(mapper.convert(1, 4)).isZero();
            assertThat(mapper.convert(1, 3)).isEqualTo(1);
            Map<String,Object> complete = session.selectOne("lockFixture.read");
            assertThat(complete.get("status")).isEqualTo("CONVERTED");
            assertThat(((Number) complete.get("remaining")).intValue()).isZero();
            assertThat(mapper.convert(1, 1)).isZero();
            session.rollback();
        }
    }
}
