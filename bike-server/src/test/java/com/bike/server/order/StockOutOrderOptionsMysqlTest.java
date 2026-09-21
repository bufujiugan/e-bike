package com.bike.server.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "BIKE_MYSQL_TEST_URL", matches = ".+")
class StockOutOrderOptionsMysqlTest {
    @Test void filtersBeforePaginationAndFindsOrdersBehindMoreThan100RecentDrafts() throws Exception {
        var source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", System.getenv("BIKE_MYSQL_TEST_URL"),
                System.getenv("BIKE_MYSQL_TEST_USER"), System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
        String prefix = "accept_orderopts_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_";
        var tables = new ArrayList<String>();
        var configuration = new Configuration(new Environment("order-options", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        try (var input = Resources.getResourceAsStream("mapper/StockOutOrderOptionsMapper.xml")) {
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", prefix);
            new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), configuration,
                    "order-options", configuration.getSqlFragments()).parse();
        }
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession();
                var sql = session.getConnection().createStatement()) {
            for (String ddl : List.of(
                    "bike_sales_order(id BIGINT PRIMARY KEY,order_no VARCHAR(64),customer_id BIGINT,status VARCHAR(32),updated_at DATETIME)",
                    "bike_sales_order_item(order_id BIGINT,product_model_id BIGINT,ordered_qty INT,shipped_qty INT)",
                    "bike_product_model(id BIGINT PRIMARY KEY,model_name VARCHAR(100))")) {
                String isolated = ddl.replace("bike_", prefix);
                sql.execute("CREATE TABLE " + isolated); tables.add(isolated.substring(0, isolated.indexOf('(')));
            }
            sql.execute("INSERT INTO " + prefix + "product_model VALUES(1,'分页车型'),(2,'独立车型')");
            try (var orders = session.getConnection().prepareStatement("INSERT INTO " + prefix
                    + "sales_order VALUES(?,?,?,?,?)");
                    var items = session.getConnection().prepareStatement("INSERT INTO " + prefix
                    + "sales_order_item VALUES(?,?,?,?)")) {
                for (int id = 1; id <= 260; id++) {
                    String state = id <= 105 ? (id % 2 == 0 ? "PARTIAL_SHIPPED" : "CONFIRMED") : "DRAFT";
                    int shipped = id <= 105 && id % 2 == 0 ? 2 : 0;
                    long customer = 7;
                    if (id == 256) state = "CANCELLED";
                    if (id == 257) state = "CLOSED";
                    if (id == 258) state = "SHIPPED";
                    if (id == 259) { state = "CONFIRMED"; shipped = 5; }
                    if (id == 260) { state = "CONFIRMED"; customer = 8; }
                    orders.setInt(1, id); orders.setString(2, "SO-" + String.format("%04d", id));
                    orders.setLong(3, customer); orders.setString(4, state);
                    orders.setString(5, id <= 105 ? "2026-01-01 00:00:00" : "2026-09-01 00:00:00"); orders.addBatch();
                    items.setInt(1, id); items.setInt(2, id == 7 ? 2 : 1); items.setInt(3, 5); items.setInt(4, shipped); items.addBatch();
                }
                orders.executeBatch(); items.executeBatch();
            }
            var mapper = session.getMapper(StockOutOrderOptionsMapper.class);
            assertThat(mapper.count(7, null)).isEqualTo(105);
            var actual = new ArrayList<StockOutOrderOption>();
            for (int page = 0; page < 6; page++) actual.addAll(mapper.selectPage(7, null, page * 20L, 20));
            var expectedIds = java.util.stream.LongStream.rangeClosed(1, 105).boxed()
                    .sorted(java.util.Comparator.reverseOrder()).toList();
            assertThat(actual).extracting(StockOutOrderOption::id).containsExactlyElementsOf(expectedIds);
            assertThat(actual).allSatisfy(row -> {
                assertThat(row.customerId()).isEqualTo(7);
                assertThat(row.status()).isIn("CONFIRMED", "PARTIAL_SHIPPED");
                assertThat(row.orderQty()).isGreaterThan(row.shippedQty());
            });
            assertThat(mapper.selectPage(7, "SO-0001", 0, 20)).extracting(StockOutOrderOption::id).containsExactly(1L);
            assertThat(mapper.count(7, "SO-0001")).isEqualTo(1);
            assertThat(mapper.selectPage(7, "独立车型", 0, 20)).extracting(StockOutOrderOption::id).containsExactly(7L);
            assertThat(mapper.count(7, "独立车型")).isEqualTo(1);
            assertThat(mapper.count(8, null)).isEqualTo(1);
            assertThat(mapper.selectPage(7, "SO-0260", 0, 20)).isEmpty();
            assertThat(mapper.selectPage(7, null, 120, 20)).isEmpty();
        } finally {
            Collections.reverse(tables);
            try (var connection = source.getConnection(); var sql = connection.createStatement()) {
                for (String table : tables) {
                    assertThat(table).startsWith(prefix).matches("[a-z0-9_]+");
                    sql.execute("DROP TABLE " + table);
                }
            }
        }
    }
}
