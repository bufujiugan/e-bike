package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.BusinessException;
import com.bike.server.common.OperationLogMapper;
import com.bike.server.order.SalesOrderView;
import com.bike.server.order.OrderMapper;
import com.bike.server.order.OrderReservationMapper;
import com.bike.server.order.OrderReservationCoordinator;
import com.bike.server.order.OrderReservationService;
import com.bike.server.order.OrderReservationExpiryJob;
import com.bike.server.production.ProductionGuard;
import com.bike.server.production.ProductionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL with the production mapper, migration DDL, services and independent transactions. */
@EnabledIfEnvironmentVariable(named = "BIKE_MYSQL_TEST_URL", matches = ".+")
class PiMysqlTest {
    @Test
    void draftCanBeRepairedWithoutChangingIdentityAndCancellationKeepsOrderGate() throws Exception {
        try (var f = new Fixture()) {
            PiCreateRequest original = f.request(null, 1L);
            PiCreateRequest incomplete = new PiCreateRequest(original.customerId(), original.agentId(), original.sourceOrderId(),
                    original.tradeType(), original.currencyCode(), null, null, null, null,
                    original.depositRequiredAmount(), original.shipmentRequiredAmount(), "待补商务条款", null, original.items());
            PiView pi = f.transaction(() -> f.service.create(incomplete));
            assertThatThrownBy(() -> f.transaction(() -> f.service.confirm(pi.getId()))).isInstanceOf(BusinessException.class);
            PiView updated = f.transaction(() -> f.service.update(pi.getId(), original));
            assertThat(updated.getPiNo()).isEqualTo(pi.getPiNo());
            assertThat(updated.getRevision()).isEqualTo(pi.getRevision());
            assertThat(updated.getDocument().sellerName()).isEqualTo("卖方");
            assertThat(updated.getItems()).hasSize(2);
            f.transaction(() -> f.service.confirm(pi.getId()));
            PiView cancelled = f.transaction(() -> f.service.cancel(pi.getId(), new PiCancelRequest("客户取消未付款订单")));
            assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
            assertThat(cancelled.getReleaseStatus()).isEqualTo("BLOCKED");
            assertThat(cancelled.getSourceOrderId()).isEqualTo(1L);
            assertThatThrownBy(() -> f.transaction(() -> f.service.create(original)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("ORDER_PI_EXISTS"));
            var order = new SalesOrderView();
            order.setId(1L);
            order.setCustomerId(1L);
            assertThatThrownBy(() -> f.transaction(() -> new PiShipmentGuard(f.shipments).requireReleased(order)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("PI_SHIPMENT_NOT_RELEASED"));
        }
    }

    @Test
    void auditFailureRollsBackFinancialWrite() throws Exception {
        try (var f = new Fixture()) {
            long id = f.confirmed();
            PiServiceImpl failingAudit = new PiServiceImpl(f.mapper, mock(PiDocumentService.class),
                    mock(OperationLogMapper.class), f.json, f.validation.getValidator(), f.coordinator);
            assertThatThrownBy(() -> f.transaction(() -> failingAudit.recordPayment(id, PiServiceTest.payment("rollback", "30.00"))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("PI_AUDIT_FAILED"));
            PiView saved = f.transaction(() -> f.service.get(id));
            assertThat(saved.getPayments()).isEmpty();
            assertThat(saved.getPaidAmount()).isEqualByComparingTo("0.00");
            assertThat(saved.getPaymentStatus()).isEqualTo("UNPAID");
        }
    }

    @Test
    void physicalShipmentRequiresReleaseAndExactSkuWithRemainingQuantity() throws Exception {
        try (var f = new Fixture()) {
            PiView pi = f.transaction(() -> f.service.create(f.request(null, 1L)));
            f.transaction(() -> f.service.confirm(pi.getId()));
            var guard = new PiShipmentGuard(f.shipments);
            var order = new SalesOrderView();
            order.setId(1L);
            order.setCustomerId(1L);
            assertThatThrownBy(() -> f.transaction(() -> guard.requireReleased(order)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("PI_SHIPMENT_NOT_RELEASED"));
            f.transaction(() -> f.service.recordPayment(pi.getId(), PiServiceTest.payment("full", "100.00")));
            assertThatThrownBy(() -> f.transaction(() -> guard.requireReleased(order))).isInstanceOf(BusinessException.class);
            f.transaction(() -> f.service.release(pi.getId(), new PiReleaseRequest("已核对条件")));
            PiShipmentGate gate = f.transaction(() -> guard.requireReleased(order));
            f.transaction(() -> { guard.requireVehicle(gate, 1L, 100L); return null; });
            assertThatThrownBy(() -> f.transaction(() -> { guard.requireVehicle(gate, 1L, 102L); return null; }))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("PI_SKU_MISMATCH"));
            f.execute("INSERT INTO " + f.prefix + "stock_out_record(id,vehicle_id,order_id,status) VALUES(1,100,1,'COMPLETED')");
            assertThatThrownBy(() -> f.transaction(() -> { guard.requireVehicle(gate, 1L, 103L); return null; }))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("PI_SKU_QTY_EXCEEDED"));
            f.transaction(() -> { guard.requireVehicle(gate, 1L, 101L); return null; });
            f.execute("UPDATE " + f.prefix + "stock_out_record SET status='CANCELLED'");
            f.transaction(() -> { guard.requireVehicle(gate, 1L, 103L); return null; });
        }
    }

    @Test
    void multiSkuSnapshotsSurviveMasterEditsAndAgentCannotReadDrafts() throws Exception {
        try (var f = new Fixture()) {
            PiView pi = f.transaction(() -> f.service.create(f.request(9L, null)));
            assertThat(pi.getItems()).hasSize(2);
            assertThat(pi.getTotalAmount()).isEqualByComparingTo("100.00");
            assertThat(pi.getItems()).extracting(PiItemView::getSkuCode).containsExactly("RED", "BLUE");
            f.execute("UPDATE " + f.prefix + "customer SET customer_name='new customer';");
            f.execute("UPDATE " + f.prefix + "production_sku SET product_name='new product';");
            f.execute("UPDATE " + f.prefix + "product_model SET model_name='new model';");
            PiView saved = f.transaction(() -> f.service.get(pi.getId()));
            assertThat(saved.getCustomerName()).isEqualTo("旧客户");
            assertThat(saved.getItems()).extracting(PiItemView::getProductName).containsExactly("红色车", "蓝色车");
            assertThat(saved.getItems()).extracting(PiItemView::getModelName).containsOnly("城市车");
            f.agent(9L, () -> {
                assertThat(f.service.list(null, null, 1, 20).total()).isZero();
                assertThatThrownBy(() -> f.service.get(pi.getId())).isInstanceOf(BusinessException.class);
                return null;
            });
            f.transaction(() -> f.service.confirm(pi.getId()));
            f.agent(9L, () -> {
                assertThat(f.service.list(null, null, 1, 20).total()).isEqualTo(1);
                assertThat(f.service.get(pi.getId()).getPayments()).isEmpty();
                return null;
            });
            f.agent(10L, () -> {
                assertThat(f.service.list(null, null, 1, 20).total()).isZero();
                assertThatThrownBy(() -> f.service.get(pi.getId())).isInstanceOf(BusinessException.class);
                return null;
            });
        }
    }

    @Test
    void concurrentPaymentsAndRetriesDoNotLoseTotalsOrDoubleBook() throws Exception {
        try (var f = new Fixture(); var pool = Executors.newFixedThreadPool(6)) {
            long id = f.confirmed();
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<PiView>>();
            for (int i = 0; i < 8; i++) {
                String key = i % 2 == 0 ? "deposit" : "balance";
                String amount = i % 2 == 0 ? "30.00" : "70.00";
                futures.add(pool.submit(() -> {
                    await(start);
                    return f.transaction(() -> f.service.recordPayment(id, PiServiceTest.payment(key, amount)));
                }));
            }
            start.countDown();
            for (Future<PiView> future : futures) future.get(15, TimeUnit.SECONDS);
            PiView saved = f.transaction(() -> f.service.get(id));
            assertThat(saved.getPaidAmount()).isEqualByComparingTo("100.00");
            assertThat(saved.getPaymentStatus()).isEqualTo("PAID");
            assertThat(saved.getPayments()).hasSize(2);
            assertThat(f.scalar("SELECT COUNT(*) FROM " + f.prefix + "operation_log WHERE action='登记 PI 收款'")).isEqualTo(2);
            assertThatThrownBy(() -> f.transaction(() -> f.service.recordPayment(id, PiServiceTest.payment("deposit", "31.00"))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("PAYMENT_REQUEST_CONFLICT"));
        }
    }

    @Test
    void releaseWaitsForPaymentCommitThenUsesFreshActualTotal() throws Exception {
        try (var f = new Fixture(); var pool = Executors.newFixedThreadPool(2)) {
            long id = f.confirmed();
            f.transaction(() -> f.service.recordPayment(id, PiServiceTest.payment("deposit", "30.00")));
            assertThatThrownBy(() -> f.transaction(() -> f.service.release(id, new PiReleaseRequest("核对条件"))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("PAYMENT_GATE_NOT_MET"));
            var inserted = new CountDownLatch(1);
            var commit = new CountDownLatch(1);
            var releaseStarted = new CountDownLatch(1);
            PiMapper paymentMapper = proxy(f.mapper, (method, before) -> {
                if (method.equals("insertPayment") && !before) { inserted.countDown(); await(commit); }
            });
            PiServiceImpl payments = f.service(paymentMapper), releaser = f.service(f.mapper);
            Future<PiView> payment = pool.submit(() -> f.transaction(() -> payments.recordPayment(id, PiServiceTest.payment("balance", "70.00"))));
            try {
                await(inserted);
                Future<PiView> release = pool.submit(() -> {
                    releaseStarted.countDown();
                    return f.transaction(() -> releaser.release(id, new PiReleaseRequest("已核对全额收款")));
                });
                await(releaseStarted);
                assertThatThrownBy(() -> release.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                commit.countDown();
                payment.get(15, TimeUnit.SECONDS);
                assertThat(release.get(15, TimeUnit.SECONDS).getReleaseStatus()).isEqualTo("RELEASED");
            } finally { commit.countDown(); }
        }
    }

    @Test
    void sourceOrderMustMatchAndCannotAcquireTwoPiDocuments() throws Exception {
        try (var f = new Fixture(); var pool = Executors.newFixedThreadPool(2)) {
            var request = f.request(null, 1L);
            var start = new CountDownLatch(1);
            Callable<String> create = () -> {
                await(start);
                try { f.transaction(() -> f.service.create(request)); return "SUCCESS"; }
                catch (BusinessException error) { return error.getCode(); }
            };
            Future<String> first = pool.submit(create), second = pool.submit(create);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "ORDER_PI_EXISTS");
            assertThat(f.scalar("SELECT COUNT(*) FROM " + f.prefix + "pi")).isEqualTo(1);
        }
    }

    @Test
    void alreadyShippedOrderCannotHaveNewPiAndReadUnrelatedSourceIsRejected() throws Exception {
        try (var f = new Fixture()) {
            assertThatThrownBy(() -> f.transaction(() -> f.service.create(f.request(9L, 1L))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("ORDER_NOT_FOUND"));
            f.execute("UPDATE " + f.prefix + "sales_order_item SET shipped_qty=1");
            assertThatThrownBy(() -> f.transaction(() -> f.service.create(f.request(null, 1L))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("ORDER_ALREADY_SHIPPED"));
            assertThat(f.scalar("SELECT COUNT(*) FROM " + f.prefix + "pi")).isZero();
        }
    }

    private interface Checkpoint { void visit(String method, boolean before); }

    private static PiMapper proxy(PiMapper delegate, Checkpoint checkpoint) {
        return (PiMapper) Proxy.newProxyInstance(PiMapper.class.getClassLoader(), new Class<?>[]{PiMapper.class}, (self, method, args) -> {
            checkpoint.visit(method.getName(), true);
            try {
                Object value = method.invoke(delegate, args);
                checkpoint.visit(method.getName(), false);
                return value;
            } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).as("transaction checkpoint").isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }

    static final class Fixture implements AutoCloseable {
        final String prefix = "accept_pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "_";
        final UnpooledDataSource source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                System.getenv("BIKE_MYSQL_TEST_URL"), System.getenv("BIKE_MYSQL_TEST_USER"), System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
        final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        final List<String> tables = new ArrayList<>();
        final PiMapper mapper;
        final PiShipmentMapper shipments;
        final OperationLogMapper logs;
        final OrderMapper orders;
        final OrderReservationMapper reservations;
        final ProductionGuard guard;
        final OrderReservationCoordinator coordinator;
        final OrderReservationService reservationService;
        final OrderReservationExpiryJob expiryJob;
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final jakarta.validation.ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
        final PiServiceImpl service;

        Fixture() throws Exception {
            var config = new Configuration(new Environment("pi-business", new SpringManagedTransactionFactory(), source));
            config.setMapUnderscoreToCamelCase(true);
            for (String name : List.of("InventoryPolicyMapper", "OrderMapper", "OrderReservationMapper", "ProductionMapper", "PiMapper", "PiShipmentMapper", "OperationLogMapper")) {
                try (var input = Resources.getResourceAsStream("mapper/" + name + ".xml")) {
                    String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", prefix);
                    new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), config, name, config.getSqlFragments()).parse();
                }
            }
            var session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            mapper = session.getMapper(PiMapper.class);
            shipments = session.getMapper(PiShipmentMapper.class);
            logs = session.getMapper(OperationLogMapper.class);
            orders = session.getMapper(OrderMapper.class);
            reservations = session.getMapper(OrderReservationMapper.class);
            guard = new ProductionGuard(session.getMapper(ProductionMapper.class));
            coordinator = new OrderReservationCoordinator(guard, reservations, logs, json);
            reservationService = new OrderReservationService(orders, reservations, coordinator, mapper);
            expiryJob = new OrderReservationExpiryJob(reservations, orders, guard);
            service = service(mapper);
            try {
                for (String ddl : List.of(
                        "customer(id BIGINT PRIMARY KEY,customer_code VARCHAR(64),customer_name VARCHAR(150),status VARCHAR(32))",
                        "agent(id BIGINT PRIMARY KEY,agent_code VARCHAR(64),agent_name VARCHAR(150),status VARCHAR(32))",
                        "product_model(id BIGINT PRIMARY KEY,model_name VARCHAR(100),enabled TINYINT)",
                        "production_sku(id BIGINT PRIMARY KEY,model_id BIGINT,sku_code VARCHAR(64),product_name VARCHAR(100),specification VARCHAR(100),enabled TINYINT)",
                        "sales_order(id BIGINT PRIMARY KEY,order_no VARCHAR(64),customer_id BIGINT,agent_id BIGINT,status VARCHAR(32),planned_ship_date DATE,remark VARCHAR(500),created_at DATETIME(3),updated_at DATETIME(3))",
                        "sales_order_item(id BIGINT PRIMARY KEY,order_id BIGINT,product_model_id BIGINT,ordered_qty INT,shipped_qty INT)",
                        "production_mutex(id INT PRIMARY KEY)",
                        "inventory_lock(id BIGINT PRIMARY KEY,lock_no VARCHAR(64),product_model_id BIGINT,lock_qty INT,released_qty INT,converted_qty INT,status VARCHAR(32),expire_time DATETIME(3))",
                        "order_reservation(order_id BIGINT PRIMARY KEY,source_lock_id BIGINT,request_id VARCHAR(64),model_id BIGINT,reserved_qty INT,expires_at DATETIME(3),expiry_audited_at DATETIME(3))",
                        "vehicle(id BIGINT PRIMARY KEY,model_id BIGINT,status INT,frame_sn VARCHAR(64))",
                        "production_issue(code VARCHAR(64),status VARCHAR(32))",
                        "vehicle_part_binding(vehicle_id BIGINT,part_sn VARCHAR(64),is_current INT)",
                        "sales_order_vehicle(order_id BIGINT,vehicle_id BIGINT,stock_out_record_id BIGINT)",
                        "production_vehicle(vehicle_id BIGINT PRIMARY KEY,sku_id BIGINT)",
                        "stock_out_record(id BIGINT PRIMARY KEY,vehicle_id BIGINT,order_id BIGINT,status VARCHAR(32),stock_out_no VARCHAR(64),operation_time DATETIME(3))",
                        "operation_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,business_type VARCHAR(64),business_id VARCHAR(64),action VARCHAR(64),operator_id BIGINT,operator_name VARCHAR(100),before_data JSON,after_data JSON,client_ip VARCHAR(64),remark VARCHAR(500))")) {
                    create("CREATE TABLE " + prefix + ddl + " ENGINE=InnoDB");
                }
                for (String file : List.of("V24__pi_multi_item_and_fulfillment.sql", "V30__pi_series_and_atomic_conversion.sql",
                        "V33__pi_revisions_and_series_payments.sql")) {
                    try (var input = Resources.getResourceAsStream("db/migration/" + file)) {
                        String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8).split("INSERT INTO bike_sys_permission")[0]
                                .replace("bike_", prefix).replaceAll("CONSTRAINT (fk_(?:pi|dispatch|logistics)_[a-z_]+)", "CONSTRAINT "+prefix+"$1");
                        for (String statement : ddl.split(";")) {
                            String clean = statement.trim();
                            if (clean.isBlank()) continue;
                            if (clean.toUpperCase(Locale.ROOT).startsWith("CREATE TABLE")) create(clean); else execute(clean);
                        }
                    }
                }
                try (var input = Resources.getResourceAsStream("db/migration/V25__paid_order_reservation.sql")) {
                    String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", prefix)
                            .replace("fk_reservation_payment_pi", prefix + "payment_pi").replace("ck_reservation_hold_mode", prefix + "hold_mode");
                    execute(ddl.substring(0, ddl.indexOf(';')));
                }
                execute("INSERT INTO " + prefix + "production_mutex VALUES(1)");
                execute("INSERT INTO " + prefix + "customer VALUES(1,'CUSTOMER','旧客户','ENABLED')");
                execute("INSERT INTO " + prefix + "agent VALUES(9,'AGENT','代理商','ENABLED')");
                execute("INSERT INTO " + prefix + "product_model VALUES(4,'城市车',1)");
                execute("INSERT INTO " + prefix + "production_sku VALUES(10,4,'RED','红色车','红色',1),(11,4,'BLUE','蓝色车','蓝色',1)");
                execute("INSERT INTO " + prefix + "sales_order(id,order_no,customer_id,agent_id,status) VALUES(1,'SO-1',1,NULL,'DRAFT')");
                execute("INSERT INTO " + prefix + "sales_order_item VALUES(1,1,4,2,0)");
                execute("INSERT INTO " + prefix + "vehicle VALUES(100,4,2,'F100'),(101,4,2,'F101')");
                execute("INSERT INTO " + prefix + "production_vehicle VALUES(100,10),(101,11),(102,12),(103,10)");
            } catch (Exception error) { close(); throw error; }
        }

        PiServiceImpl service(PiMapper selectedMapper) {
            return new PiServiceImpl(selectedMapper, mock(PiDocumentService.class), logs, json, validation.getValidator(), coordinator);
        }

        PiCreateRequest request(Long agentId, Long orderId) {
            var doc = new PiDocumentSnapshot("卖方", null, null, null, null, null, null, null,
                    "约定定金30，发货前收足100", "按合同交付", "DOMESTIC", "1");
            return new PiCreateRequest(1L, agentId, orderId, "DOMESTIC", "CNY", null, null, null, null,
                    new BigDecimal("30.00"), new BigDecimal("100.00"), null, doc,
                    List.of(PiServiceTest.item(10L, 1, "40.00", null, null), PiServiceTest.item(11L, 1, "60.00", null, null)));
        }

        long confirmed() {
            PiView pi = transaction(() -> service.create(request(null, null)));
            transaction(() -> service.confirm(pi.getId()));
            return pi.getId();
        }

        <T> T transaction(Supplier<T> action) {
            PiServiceTest.login("ROLE_SUPER_ADMIN");
            try { return tx.execute(status -> action.get()); }
            finally { SecurityContextHolder.clearContext(); }
        }

        <T> T at(long databaseSecond, Supplier<T> action) {
            return transaction(() -> {
                var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
                jdbc.execute("SET timestamp=" + databaseSecond);
                return action.get();
            });
        }

        <T> T agent(long id, Supplier<T> action) {
            var auth = new UsernamePasswordAuthenticationToken("agent", null,
                    List.of(new SimpleGrantedAuthority("ROLE_WEB_AGENT"), new SimpleGrantedAuthority("PERM_PI_READ")));
            auth.setDetails(new UserAuthRecord(id, "agent", "", "WEB_AGENT", id, null, true));
            SecurityContextHolder.getContext().setAuthentication(auth);
            try { return action.get(); }
            finally { SecurityContextHolder.clearContext(); }
        }

        void create(String sql) throws Exception {
            execute(sql);
            String table = sql.replaceFirst("CREATE TABLE (IF NOT EXISTS )?", "").split("[ (\\r\\n]", 2)[0];
            tables.add(table);
        }

        void execute(String statement) throws Exception {
            try (var connection = source.getConnection(); var sql = connection.createStatement()) { sql.execute(statement); }
        }

        long scalar(String statement) throws Exception {
            try (var connection = source.getConnection(); var sql = connection.createStatement(); var row = sql.executeQuery(statement)) {
                row.next(); return row.getLong(1);
            }
        }

        @Override
        public void close() throws Exception {
            validation.close();
            if (tables.contains(prefix + "order_reservation")) {
                execute("DROP TABLE " + prefix + "order_reservation");
                tables.remove(prefix + "order_reservation");
            }
            if (tables.stream().anyMatch(t -> t.equals(prefix + "pi_series")))
                execute("ALTER TABLE " + prefix + "pi DROP FOREIGN KEY " + prefix + "fk_pi_series");
            for (String table : tables.reversed()) {
                assertThat(table).startsWith(prefix).matches("[a-z0-9_]+");
                execute("DROP TABLE " + table);
            }
        }
    }
}
