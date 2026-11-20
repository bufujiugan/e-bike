package com.bike.server.order;

import static org.assertj.core.api.Assertions.*;

import com.bike.server.production.migration.*;
import java.sql.DriverManager;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * V59 销售类型及单据资料迁移、订单生效销售类型 SQL、列表筛选与一次性补选。
 * 仅在本机专用 MySQL（127.0.0.1:3337）上执行，库名固定为独立测试库，不访问业务库。
 */
@EnabledIfEnvironmentVariable(named = "BIKE_SALES_ROUTING_MYSQL_TEST", matches = "true")
class SalesDocumentRoutingMysqlTest {
    private static final String BASE = "jdbc:mysql://127.0.0.1:3337/";
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&allowMultiQueries=false";
    private static final String DATABASE = "bike_sales_routing_test_20261120";

    @Test void migratesV59AndClassifiesOrdersBySelectedTypeOrLinkedPi() throws Exception {
        try (var connection = DriverManager.getConnection(BASE + OPTIONS, "root", ""); var sql = connection.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + DATABASE);
            sql.execute("CREATE DATABASE " + DATABASE + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        var source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", BASE + DATABASE + OPTIONS, "root", "");
        var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .javaMigrations(new V16__production_workflow(), new V57__unified_fulfillment(), new V58__europe_sales(), new V59__sales_document_routing())
                .table("bike_schema_history").load();
        flyway.migrate(); flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("59");
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            for (String column : List.of("bike_sales_order.sales_type", "bike_pi_template.bank_address", "bike_pi_template.swift_code",
                    "bike_pi_template.bank_code", "bike_pi_template.branch_code", "bike_pi_template.seller_registration_no", "bike_pi_template.seller_vat_no")) {
                String[] parts = column.split("\\.");
                try (var result = sql.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='" + DATABASE
                        + "' AND table_name='" + parts[0] + "' AND column_name='" + parts[1] + "' AND is_nullable='YES'")) {
                    result.next(); assertThat(result.getInt(1)).as(column).isEqualTo(1);
                }
            }
            try (var result = sql.executeQuery("SELECT COUNT(*),COUNT(sales_type) FROM bike_sales_order")) {
                result.next();
                System.out.println("seed orders=" + result.getInt(1) + " typed=" + result.getInt(2));
                assertThat(result.getInt(2)).as("迁移不批量猜测历史订单类型").isZero();
            }
        }
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("routing-test", null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        auth.setDetails(new com.bike.server.auth.UserAuthRecord(1L, "routing-test", "unused", "INTERNAL", null, null, true));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try (var session = sessions(source).openSession(false)) {
            var orders = session.getMapper(OrderMapper.class);
            long domestic = order(orders, "SO-RT-DOMESTIC", SalesType.DOMESTIC);
            long pending = order(orders, "SO-RT-PENDING", null);
            long legacyExport = order(orders, "SO-RT-LEGACY-EXPORT", null);
            long czech = order(orders, "SO-RT-CZECH", null);
            long abroad = order(orders, "SO-RT-ABROAD", null);
            long legacyDomestic = order(orders, "SO-RT-LEGACY-DOMESTIC", null);
            long factory = order(orders, "SO-RT-FACTORY", null);
            long explicitExport = order(orders, "SO-RT-EXPORT", SalesType.EXPORT);
            try (var sql = session.getConnection().createStatement()) {
                pi(sql, 9001, "PI-RT-1", legacyExport, "EXPORT", "{\"business\":{\"domesticShipment\":true}}");
                pi(sql, 9003, "PI-RT-3", abroad, "DOMESTIC", "{\"business\":{\"domesticShipment\":false}}");
                pi(sql, 9004, "PI-RT-4", legacyDomestic, "DOMESTIC", "{\"business\":{\"domesticShipment\":true}}");
                // 捷克 PI 通过系列关联表关联订单，同一订单的较早 DOMESTIC 历史修订不影响最新归属。
                pi(sql, 9002, "PI-RT-2", null, "EXPORT", "{\"business\":{\"domesticShipment\":false,\"salesChannel\":\"CZECH_PI\"}}");
                sql.executeUpdate("INSERT INTO bike_pi_series(id,pi_no,current_pi_id) VALUES(9002,'PI-RT-2',9002)");
                sql.executeUpdate("UPDATE bike_pi SET series_id=9002 WHERE id=9002");
                sql.executeUpdate("INSERT INTO bike_pi_order_link(order_id,series_id,created_pi_id,link_type) VALUES(" + czech + ",9002,9002,'SKU_CONVERSION')");
                sql.executeUpdate("INSERT INTO bike_factory_sales_order(id,order_no,request_id,customer_id,business_type,currency_code,details_json,steps_json) "
                        + "VALUES(9101,'FS-RT-1','FS-RT-REQUEST',1,'FACTORY_EXPORT','EUR','{}','{}')");
                sql.executeUpdate("INSERT INTO bike_factory_sales_order_link(factory_order_id,order_id) VALUES(9101," + factory + ")");
            }
            assertThat(orders.selectById(domestic).getSalesType()).isEqualTo(SalesType.DOMESTIC);
            assertThat(orders.selectById(domestic).getSelectedSalesType()).isEqualTo(SalesType.DOMESTIC);
            assertThat(orders.selectById(pending).getSalesType()).isNull();
            assertThat(orders.selectById(legacyExport).getSalesType()).isEqualTo(SalesType.EXPORT);
            assertThat(orders.selectById(legacyExport).getSelectedSalesType()).isNull();
            assertThat(orders.selectById(czech).getSalesType()).isEqualTo(SalesType.EUROPE);
            assertThat(orders.selectById(abroad).getSalesType()).isEqualTo(SalesType.EUROPE);
            assertThat(orders.selectById(legacyDomestic).getSalesType()).isEqualTo(SalesType.DOMESTIC);
            assertThat(orders.selectById(factory).getSalesType()).isEqualTo(SalesType.EUROPE);
            assertThat(orders.selectById(explicitExport).getSalesType()).isEqualTo(SalesType.EXPORT);

            var domesticTab = List.of(SalesType.DOMESTIC, SalesType.PENDING);
            var numbers = orders.selectPage("SO-RT", null, null, domesticTab, 0, 50).stream().map(SalesOrderView::getOrderNo).toList();
            assertThat(numbers).containsExactlyInAnyOrder("SO-RT-DOMESTIC", "SO-RT-PENDING", "SO-RT-LEGACY-DOMESTIC");
            assertThat(orders.count("SO-RT", null, null, domesticTab)).isEqualTo(3);
            assertThat(orders.count("SO-RT", null, null, List.of(SalesType.EUROPE))).isEqualTo(3);
            assertThat(orders.count("SO-RT", null, null, null)).isEqualTo(8);

            var modules = session.getMapper(com.bike.server.module.ModuleMapper.class);
            var columns = List.of("订单号", "客户", "车型", "订单数量", "已出库", "计划发货", "状态", "来源锁定", "占用截止", "占用状态", "备注", "创建时间", "修改时间", "销售类型");
            var rows = modules.selectRowsPage("orders", null, columns, "so-rt", "创建时间", 0, 50, false, domesticTab);
            assertThat(rows).extracting(row -> row.get("订单号")).containsExactlyInAnyOrder("SO-RT-DOMESTIC", "SO-RT-PENDING", "SO-RT-LEGACY-DOMESTIC");
            assertThat(rows).extracting(row -> row.get("销售类型")).contains("待选类型", "内销 · 购销合同");
            assertThat(modules.countRows("orders", null, columns, "so-rt", false, List.of(SalesType.EXPORT))).isEqualTo(2);
            assertThat(modules.countRows("orders", null, columns, "so-rt", false)).isEqualTo(8);

            assertThat(orders.assignSalesType(pending, SalesType.EXPORT)).isEqualTo(1);
            assertThat(orders.assignSalesType(pending, SalesType.DOMESTIC)).as("已选类型不可覆盖").isZero();
            assertThat(orders.selectById(pending).getSalesType()).isEqualTo(SalesType.EXPORT);

            var templates = session.getMapper(com.bike.server.pi.PiTemplateMapper.class);
            var request = new com.bike.server.pi.PiTemplateRequest("RT_EXPORT", "V1", "EXPORT", "EXPORT_V3", "Seller", "Address", "", "Bank", "123",
                    "Payee", "test", "HK", "Sales", "+852", "sales@example.com", " 32nd Floor ", "SCBLHKHH", "003", "474", "KVK-1", "VAT-1");
            assertThat(templates.publish(request, 1L)).isEqualTo(1);
            var saved = templates.find("RT_EXPORT", "V1");
            assertThat(saved.bankAddress()).isEqualTo("32nd Floor");
            assertThat(saved.swiftCode()).isEqualTo("SCBLHKHH");
            assertThat(saved.branchCode()).isEqualTo("474");
            assertThat(saved.sellerVatNo()).isEqualTo("VAT-1");
            var legacy = new com.bike.server.pi.PiTemplateRequest("RT_LEGACY", "V1", "EXPORT", "EXPORT_V3", "Seller", "Address", "", "Bank", "123",
                    "Payee", "test", "HK", "Sales", "+852", "sales@example.com");
            assertThat(templates.publish(legacy, 1L)).isEqualTo(1);
            assertThat(templates.find("RT_LEGACY", "V1").swiftCode()).isNull();

            var options = session.getMapper(com.bike.server.pi.PiMapper.class).selectOrderOptions("SO-RT-EXPORT");
            assertThat(options).isNotEmpty();
            assertThat(options.getFirst().get("salesType")).isEqualTo(SalesType.EXPORT);
            session.rollback();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private static long order(OrderMapper orders, String no, String type) {
        var request = new SalesOrderCreateRequest(1L, null, 1L, 1, null, "routing test", type);
        assertThat(orders.insertOrder(no, request, null)).isEqualTo(1);
        assertThat(orders.insertItem(no, request)).isEqualTo(1);
        return orders.selectByNo(no).getId();
    }

    private static void pi(java.sql.Statement sql, long id, String no, Long sourceOrderId, String trade, String document) throws java.sql.SQLException {
        sql.executeUpdate("INSERT INTO bike_pi(id,pi_no,customer_id,customer_code,customer_name,deposit_required_amount,shipment_required_amount,trade_type,source_order_id,document_snapshot) VALUES("
                + id + ",'" + no + "',1,'C1','客户',0,0,'" + trade + "'," + sourceOrderId + ",'" + document + "')");
    }

    private static SqlSessionFactory sessions(UnpooledDataSource source) throws Exception {
        var config = new Configuration(new Environment("routing-test", new JdbcTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        for (String resource : List.of("mapper/InventoryPolicyMapper.xml", "mapper/AuditPolicyMapper.xml", "mapper/OrderMapper.xml",
                "mapper/ModuleMapper.xml", "mapper/PiTemplateMapper.xml", "mapper/PiMapper.xml"))
            try (var input = Resources.getResourceAsStream(resource)) { new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse(); }
        for (Class<?> type : List.of(OrderMapper.class, com.bike.server.module.ModuleMapper.class, com.bike.server.pi.PiTemplateMapper.class, com.bike.server.pi.PiMapper.class))
            if (!config.hasMapper(type)) config.addMapper(type);
        return new SqlSessionFactoryBuilder().build(config);
    }
}
