package com.bike.server.module;

import com.bike.server.dashboard.DashboardMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** Uses isolated tables to exercise the real SQL before filtering, counting and pagination. */
@EnabledIfEnvironmentVariable(named = "BIKE_MYSQL_TEST_URL", matches = ".+")
class AuditPrivacyMysqlTest {
    @Test void financeEventsCannotBeDiscoveredThroughGeneralSearchTraceOrDashboard() throws Exception {
        var source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", System.getenv("BIKE_MYSQL_TEST_URL"),
                System.getenv("BIKE_MYSQL_TEST_USER"), System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
        var configuration = new Configuration(new Environment("audit-privacy", new JdbcTransactionFactory(), source));
        String prefix = "accept_audit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_";
        for (String name : List.of("AuditPolicyMapper", "InventoryPolicyMapper", "ModuleMapper", "DashboardMapper")) {
            try (var stream = Resources.getResourceAsStream("mapper/" + name + ".xml")) {
                String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", prefix);
                new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), configuration,
                        name, configuration.getSqlFragments()).parse();
            }
        }
        var tables = new ArrayList<String>();
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
             var sql = session.getConnection().createStatement()) {
            for (String ddl : List.of(
                    "operation_log(id BIGINT, business_type VARCHAR(50), business_id VARCHAR(50), action VARCHAR(100), frame_sn VARCHAR(64), operator_name VARCHAR(64), remark VARCHAR(500), operation_time DATETIME(3))",
                    "vehicle(id BIGINT, frame_sn VARCHAR(64), status INT, current_order_no VARCHAR(64), current_customer_id BIGINT)",
                    "customer(id BIGINT, customer_name VARCHAR(100))")) {
                String table = prefix + ddl.substring(0, ddl.indexOf('('));
                sql.execute("CREATE TABLE " + prefix + ddl);
                tables.add(table);
            }
            sql.execute("INSERT INTO " + prefix + "operation_log VALUES "
                    + "(1,'PRODUCTION','public-production','完成组装','FRAME1','worker','public-note','2026-09-20 10:00:00'),"
                    + "(2,'PI','finance-receipt','登记 PI 收款',NULL,'finance','BANKSECRET 500.00','2026-09-20 10:01:00'),"
                    + "(3,'PI','finance-release','放行 PI 发货',NULL,'finance','支付备注','2026-09-20 10:02:00'),"
                    + "(4,'ORDER_RESERVATION','finance-retain','收款达标保留订单占用',NULL,'finance','hold-note','2026-09-20 10:03:00'),"
                    + "(5,'ORDER_RESERVATION','finance-expired','收款已达条件但占用已到期',NULL,'finance','expired-note','2026-09-20 10:04:00'),"
                    + "(6,'PI','public-confirm','确认 PI',NULL,'clerk','public-note','2026-09-20 10:05:00'),"
                    + "(7,'PI','finance-attachment','上传 PI 收款凭证',NULL,'finance','RECEIPTSECRET 银行凭证.pdf','2026-09-20 10:06:00'),"
                    + "(8,'AFTER_SALES','private-case','CASE_ASSIGNED',NULL,'service','COMPANYSECRET case','2026-09-20 10:07:00'),"
                    + "(9,'AFTER_SALES_COMPANY','private-company','COMPANY_CONTACT_UPDATED',NULL,'service','COMPANYSECRET contact','2026-09-20 10:08:00'),"
                    + "(10,'PI','finance-reverse','撤回PI错误收款登记',NULL,'finance','REVERSALSECRET reason','2026-09-20 10:09:00')");
            var mapper = session.getMapper(ModuleMapper.class);
            var columns = List.of("时间", "操作", "业务对象", "操作人", "业务类型", "备注");
            assertThat(mapper.countRows("logs", null, columns, null, false)).isEqualTo(2);
            assertThat(mapper.countRows("logs", null, columns, "banksecret", false)).isZero();
            assertThat(mapper.countRows("logs", null, columns, "companysecret", false)).isZero();
            assertThat(mapper.countRows("logs", null, columns, "companysecret", true)).isZero();
            assertThat(mapper.countRows("logs", null, columns, "receiptsecret", false)).isZero();
            assertThat(mapper.countRows("logs", null, columns, "reversalsecret", false)).isZero();
            assertThat(mapper.countRows("logs", null, columns, "reversalsecret", true)).isEqualTo(1);
            assertThat(mapper.selectRowsPage("logs", null, columns, "receiptsecret", "时间", 0, 20, false)).isEmpty();
            assertThat(mapper.countRows("logs", null, columns, "receiptsecret", true)).isEqualTo(1);
            assertThat(mapper.selectRowsPage("logs", null, columns, "banksecret", "时间", 0, 20, false)).isEmpty();
            assertThat(mapper.countRows("logs", null, columns, "banksecret", true)).isEqualTo(1);
            assertThat(mapper.selectRowsPage("logs", null, columns, "banksecret", "时间", 0, 20, true))
                    .singleElement().extracting(row -> row.get("备注")).isEqualTo("BANKSECRET 500.00");
            assertThat(mapper.selectRows("logs", null, false)).hasSize(2);
            assertThat(mapper.selectRows("trace", null, false)).hasSize(2);
            assertThat(mapper.selectRows("trace", null, true)).hasSize(8);
            assertThat(session.getMapper(DashboardMapper.class).selectRecentOperations(false))
                    .extracting(row -> row.get("target")).containsExactly("public-confirm", "FRAME1");
            assertThat(session.getMapper(DashboardMapper.class).selectRecentOperations(true)).hasSize(8);
        } finally {
            try (var connection = source.getConnection(); var sql = connection.createStatement()) {
                for (String table : tables) {
                    assertThat(table).startsWith(prefix).matches("[a-z0-9_]+");
                    sql.execute("DROP TABLE " + table);
                }
            }
        }
    }
}
