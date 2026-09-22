package com.bike.server.aftersales;

import com.bike.server.common.BusinessException;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static com.bike.server.aftersales.AfterSalesServiceTest.*;

/** Real MySQL and production SQL/service. Unique prefixed tables keep existing business data intact. */
@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL", matches=".+")
class AfterSalesMysqlTest {
    @Test void completeReviewRepairShipmentLifecycleKeepsPrivateDataAndAppendOnlyHistoryScoped() throws Exception {
        try (var f = new Fixture()) {
            var created = f.agent(() -> f.service.create(request(null, null, null)));
            long id = created.getId();
            assertThat(created.getAgentId()).isEqualTo(8L);
            assertThat(created.getStatus()).isEqualTo("DRAFT");
            assertThat(f.agent(() -> f.service.submit(id)).getStatus()).isEqualTo("SUBMITTED");
            assertCode("INVALID_STATUS_TRANSITION", () -> f.admin(() -> f.service.review(id, new AfterSalesReviewRequest("APPROVE", null))));
            f.admin(() -> f.service.review(id, new AfterSalesReviewRequest("START", null)));
            var plan=f.admin(() -> f.service.saveSolution(id, AfterSalesPlanMysqlTest.plan(null,"replacement motor",2,true))).getSolutionVersion();
            f.admin(() -> f.service.addMessage(id, new AfterSalesMessageRequest("internal diagnostic discussion", "INTERNAL")));
            f.agent(() -> f.service.addMessage(id, new AfterSalesMessageRequest("please help", "CUSTOMER")));
            var external = f.agent(() -> f.service.get(id));
            assertThat(external.getInternalNote()).isNull();
            assertThat(external.getMessages()).extracting(AfterSalesMessageView::body).containsExactly("please help");
            assertThat(external.getHistory()).extracting(AfterSalesHistoryView::action).doesNotContain("INTERNAL_MESSAGE_ADDED");
            assertThat(f.admin(() -> f.service.get(id)).getInternalNote()).isEqualTo("confidential liability");
            f.admin(()->f.service.confirmSolution(id,plan.id()));
            f.admin(() -> f.service.review(id, new AfterSalesReviewRequest("APPROVE", null,plan.id())));
            assertCode("INVALID_STATUS_TRANSITION", () -> f.admin(() -> f.service.saveSolution(id, new AfterSalesSolutionRequest(null, "changed after approval", null))));
            f.admin(() -> f.service.startProcessing(id));
            var shipment = f.admin(() -> f.service.addShipment(id, new AfterSalesShipmentRequest("replacement motor", 2, null, null, true,plan.parts().getFirst().id())));
            assertThat(shipment.getStatus()).isEqualTo("PLANNED");
            assertThat(shipment.getShippedAt()).isNull();
            assertThat(f.agent(() -> f.service.get(id)).getStatus()).isEqualTo("WAITING_PARTS");
            var sent = f.admin(() -> f.service.dispatchShipment(id, shipment.getId(), new AfterSalesDispatchRequest("DHL","AS-TRACK-001",AfterSalesPlanMysqlTest.shipping(),1)));
            assertThat(sent.getStatus()).isEqualTo("SHIPPED");
            assertThat(sent.getShippedAt()).isNotNull();
            assertThat(f.agent(() -> f.service.get(id)).getStatus()).isEqualTo("SHIPPED");
            f.admin(() -> f.service.resolve(id));
            assertCode("AFTER_SALES_RETURN_PENDING", () -> f.agent(() -> f.service.close(id)));
            var returns = new AfterSalesReturnService(f.mapper);
            f.admin(() -> returns.act(id,shipment.getId(),AfterSalesReturnMysqlTest.action(1,"WAIVE")));
            var closed = f.agent(() -> f.service.close(id));
            assertThat(closed.getStatus()).isEqualTo("CLOSED");
            assertThat(closed.getClosedAt()).isNotNull();
            assertCode("INVALID_STATUS_TRANSITION", () -> f.admin(() -> f.service.review(id, new AfterSalesReviewRequest("START", null))));
            assertCode("INVALID_STATUS_TRANSITION", () -> f.agent(() -> f.service.addMessage(id, new AfterSalesMessageRequest("too late", "CUSTOMER"))));
            assertThat(f.admin(() -> f.service.history(id))).hasSize(14);
            assertCode("FORBIDDEN", () -> f.as("AGENT", 99L, () -> f.service.get(id), "ROLE_WEB_AGENT", "PERM_AFTER_SALES_READ"));
            assertThat(f.as("AGENT", 99L, () -> f.service.list(null, null, 1, 20), "ROLE_WEB_AGENT", "PERM_AFTER_SALES_READ").total()).isZero();
        }
    }

    @Test void verifiedVehicleLinkUsesCurrentShippingAgentAndRejectsForgedReferences() throws Exception {
        try (var f = new Fixture()) {
            var own = new AfterSalesCaseCreateRequest(44L, null, 55L, "F55", 4L, null, null, null,
                null, null, "MOTOR", null, null, "stopped", null, "PENDING");
            var view = f.agent(() -> f.service.create(own));
            assertThat(view.getCustomerId()).isEqualTo(44L);
            assertThat(view.getMotorSn()).isEqualTo("M55");
            assertThat(f.agent(f.service::options).get("models")).extracting(AfterSalesReferenceOption::id).containsExactly(4L);
            assertThat(f.agent(f.service::options).get("customers")).isEmpty();
            var forged = new AfterSalesCaseCreateRequest(45L, 8L, 56L, "F56", 4L, null, null, null,
                null, null, "MOTOR", null, null, "stopped", null, "PENDING");
            assertCode("FORBIDDEN", () -> f.agent(() -> f.service.create(forged)));
            assertCode("FORBIDDEN", () -> f.agent(() -> f.service.create(request(45L, null, null))));
            f.sql("UPDATE " + f.prefix + "vehicle SET status=2 WHERE id=55");
            assertCode("FORBIDDEN", () -> f.agent(() -> f.service.create(own)));
            assertThat(f.count("after_sales_case")).isEqualTo(1);
        }
    }

    @Test void parallelMessagesReturnOwnRecordsAndCommitOneAuditEach() throws Exception {
        try (var f = new Fixture(); var pool = Executors.newFixedThreadPool(4)) {
            long id = f.submittedCase();
            List<Future<AfterSalesMessageView>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String body = "request-" + i;
                futures.add(pool.submit(() -> f.agent(() -> f.service.addMessage(id, new AfterSalesMessageRequest(body, "CUSTOMER")))));
            }
            var ids = new HashSet<Long>();
            for (int i = 0; i < futures.size(); i++) {
                var row = futures.get(i).get(15, TimeUnit.SECONDS);
                assertThat(row.body()).isEqualTo("request-" + i);
                assertThat(ids.add(row.id())).isTrue();
            }
            assertThat(f.count("after_sales_message")).isEqualTo(12);
            assertThat(f.admin(() -> f.service.history(id))).hasSize(14);
        }
    }

    @Test void closingCaseLocksOutConcurrentMessageBeforeItCanCreateAnOrphanEvent() throws Exception {
        try (var f = new Fixture(); var pool = Executors.newFixedThreadPool(2)) {
            long id = f.submittedCase();
            f.sql("UPDATE " + f.prefix + "after_sales_case SET status='RESOLVED' WHERE id=" + id);
            var locked = new CountDownLatch(1);
            var allowClose = new CountDownLatch(1);
            var messageStarted = new CountDownLatch(1);
            var closing = new AfterSalesServiceImpl(proxy(f.mapper, (name, before) -> {
                if (name.equals("selectForUpdate") && !before) { locked.countDown(); await(allowClose); }
            }));
            var messaging = new AfterSalesServiceImpl(proxy(f.mapper, (name, before) -> {
                if (name.equals("selectForUpdate") && before) messageStarted.countDown();
            }));
            Future<?> close = pool.submit(() -> f.agent(() -> closing.close(id)));
            try {
                await(locked);
                Future<String> message = pool.submit(() -> {
                    try { f.agent(() -> messaging.addMessage(id, new AfterSalesMessageRequest("racing message", "CUSTOMER"))); return "UNEXPECTED_SUCCESS"; }
                    catch (BusinessException error) { return error.getCode(); }
                });
                await(messageStarted);
                assertThatThrownBy(() -> message.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                allowClose.countDown();
                close.get(10, TimeUnit.SECONDS);
                assertThat(message.get(10, TimeUnit.SECONDS)).isEqualTo("INVALID_STATUS_TRANSITION");
                assertThat(f.count("after_sales_message")).isZero();
                assertThat(f.admin(() -> f.service.history(id))).extracting(AfterSalesHistoryView::action).containsExactly("CASE_CREATED", "CASE_SUBMITTED", "CASE_CLOSED");
            } finally { allowClose.countDown(); }
        }
    }

    @Test void failedHistoryInsertRollsBackNewMessageAndCaseTouch() throws Exception {
        try (var f = new Fixture()) {
            long id = f.submittedCase();
            var broken = new AfterSalesServiceImpl((AfterSalesMapper) Proxy.newProxyInstance(
                AfterSalesMapper.class.getClassLoader(), new Class<?>[]{AfterSalesMapper.class}, (self, method, args) -> {
                    if (method.getName().equals("insertAudit")) return 0;
                    try { return method.invoke(f.mapper, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                }));
            assertCode("AFTER_SALES_AUDIT_FAILED", () -> f.agent(() -> broken.addMessage(id, new AfterSalesMessageRequest("must roll back", "CUSTOMER"))));
            assertThat(f.count("after_sales_message")).isZero();
            assertThat(f.admin(() -> f.service.history(id))).hasSize(2);
        }
    }

    @Test void databaseRejectsInvalidShipmentStateAndQuantity() throws Exception {
        try (var f = new Fixture()) {
            long id = f.submittedCase();
            assertThatThrownBy(() -> f.sql("INSERT INTO " + f.prefix + "after_sales_shipment(case_id,part_name,quantity) VALUES(" + id + ",'motor',0)"))
                .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> f.sql("INSERT INTO " + f.prefix + "after_sales_shipment(case_id,part_name,quantity,status) VALUES(" + id + ",'motor',1,'SHIPPED')"))
                .isInstanceOf(java.sql.SQLException.class);
            assertThat(f.count("after_sales_shipment")).isZero();
        }
    }

    private interface Checkpoint { void visit(String method, boolean before); }
    private static AfterSalesMapper proxy(AfterSalesMapper mapper, Checkpoint checkpoint) {
        return (AfterSalesMapper) Proxy.newProxyInstance(AfterSalesMapper.class.getClassLoader(), new Class<?>[]{AfterSalesMapper.class}, (self, method, args) -> {
            checkpoint.visit(method.getName(), true);
            try { Object result = method.invoke(mapper, args); checkpoint.visit(method.getName(), false); return result; }
            catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }

    static final class Fixture implements AutoCloseable {
        final String prefix = "as_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "_";
        final UnpooledDataSource source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", System.getenv("BIKE_MYSQL_TEST_URL"),
            System.getenv("BIKE_MYSQL_TEST_USER"), System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
        final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        final List<String> tables = new ArrayList<>();
        final AfterSalesMapper mapper;
        final AfterSalesServiceImpl service;

        Fixture() throws Exception {
            var config = new Configuration(new Environment("after-sales", new SpringManagedTransactionFactory(), source));
            config.setMapUnderscoreToCamelCase(true);
            try (var input = Resources.getResourceAsStream("mapper/AfterSalesMapper.xml")) {
                String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", prefix);
                new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), config, "after-sales", config.getSqlFragments()).parse();
            }
            mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(AfterSalesMapper.class);
            service = new AfterSalesServiceImpl(mapper);
            try {
                for (String ddl : List.of(
                    "customer(id BIGINT PRIMARY KEY,customer_name VARCHAR(150),status VARCHAR(32),contact_name VARCHAR(100),contact_phone VARCHAR(64))",
                    "agent(id BIGINT PRIMARY KEY,agent_name VARCHAR(150),status VARCHAR(32),contact_name VARCHAR(100),contact_phone VARCHAR(64))",
                    "sys_user(id BIGINT PRIMARY KEY,username VARCHAR(64),display_name VARCHAR(100),user_type VARCHAR(32),agent_id BIGINT,enabled TINYINT)",
                    "sys_role(id BIGINT PRIMARY KEY,role_code VARCHAR(64),enabled TINYINT)",
                    "sys_permission(id BIGINT PRIMARY KEY,permission_code VARCHAR(100))",
                    "sys_user_role(user_id BIGINT,role_id BIGINT,PRIMARY KEY(user_id,role_id))",
                    "sys_role_permission(role_id BIGINT,permission_id BIGINT,PRIMARY KEY(role_id,permission_id))",
                    "product_model(id BIGINT PRIMARY KEY,model_name VARCHAR(100),enabled INT)",
                    "agent_model_access(agent_id BIGINT,model_id BIGINT,enabled INT)",
                    "sales_order(id BIGINT PRIMARY KEY,order_no VARCHAR(64),customer_id BIGINT,agent_id BIGINT)",
                    "vehicle(id BIGINT PRIMARY KEY,frame_sn VARCHAR(128),model_id BIGINT,status INT,current_customer_id BIGINT,current_order_no VARCHAR(64))",
                    "vehicle_part_binding(id BIGINT PRIMARY KEY,vehicle_id BIGINT,part_sn VARCHAR(128),part_type VARCHAR(32),is_current INT)",
                    "operation_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,business_type VARCHAR(64),business_id VARCHAR(64),action VARCHAR(64),operator_id BIGINT,operator_name VARCHAR(100),before_data JSON,after_data JSON,client_ip VARCHAR(64),operation_time DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))")) {
                    sql("CREATE TABLE " + prefix + ddl + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                    tables.add(prefix + ddl.substring(0, ddl.indexOf('(')));
                }
                try (var input = Resources.getResourceAsStream("db/migration/V23__after_sales_case_management.sql")) {
                    String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", prefix);
                    var matcher = Pattern.compile("(?s)CREATE TABLE ([a-z0-9_]+).*?;").matcher(ddl);
                    while (matcher.find()) { sql(matcher.group()); tables.add(matcher.group(1)); }
                }
                try (var input = Resources.getResourceAsStream("db/migration/V29__after_sales_company_scope_and_contacts.sql")) {
                    String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", prefix);
                    var matcher = Pattern.compile("(?s)CREATE TABLE ([a-z0-9_]+).*?;").matcher(ddl);
                    while (matcher.find()) { sql(matcher.group()); tables.add(matcher.group(1)); }
                    matcher = Pattern.compile("(?s)CREATE INDEX .*?;").matcher(ddl);
                    while (matcher.find()) sql(matcher.group());
                }
                try (var input = Resources.getResourceAsStream("db/migration/V34__after_sales_old_part_returns.sql")) {
                    String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", prefix);
                    var matcher = Pattern.compile("(?s)CREATE TABLE ([a-z0-9_]+).*?;").matcher(ddl);
                    while (matcher.find()) { sql(matcher.group()); tables.add(matcher.group(1)); }
                }
                try (var input = Resources.getResourceAsStream("db/migration/V35__after_sales_versioned_solutions.sql")) {
                    String ddl=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix);
                    try(var connection=source.getConnection()) {org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));}
                    var matcher=Pattern.compile("CREATE TABLE ([a-z0-9_]+)").matcher(ddl);
                    while(matcher.find())tables.add(matcher.group(1));
                }
                try(var input=Resources.getResourceAsStream("db/migration/V36__after_sales_shipping_details.sql")) {
                    String ddl=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix);
                    try(var connection=source.getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));}
                }
                sql("INSERT INTO " + prefix + "customer VALUES(44,'Customer A','ENABLED','Customer contact','4400'),(45,'Customer B','ENABLED',NULL,NULL)");
                sql("INSERT INTO " + prefix + "agent VALUES(8,'Agent A','ENABLED','Agent contact','8000'),(99,'Agent B','ENABLED','Other contact','9900')");
                sql("INSERT INTO " + prefix + "sys_user VALUES(7,'sales','Sales','WEB_OPERATOR',NULL,1),(70,'tech','Technician','WEB_OPERATOR',NULL,1),(71,'local-tech','Local technician','WEB_OPERATOR',NULL,1),(72,'disabled-tech','Disabled technician','WEB_OPERATOR',NULL,0),(73,'agent-a','Company employee','AGENT',8,1),(74,'pda','PDA','PDA_OPERATOR',NULL,1),(75,'agent-a2','Second employee','AGENT',8,1)");
                sql("INSERT INTO " + prefix + "sys_role VALUES(1,'SALES',1),(2,'TECH',1),(3,'LOCAL_TECH',1),(4,'SUPER_ADMIN',1)");
                sql("INSERT INTO " + prefix + "sys_permission VALUES(1,'AFTER_SALES_READ'),(2,'AFTER_SALES_TECH'),(3,'AFTER_SALES_SCOPE_ALL')");
                sql("INSERT INTO " + prefix + "sys_role_permission VALUES(1,1),(2,1),(2,2),(2,3),(3,1),(3,2)");
                sql("INSERT INTO " + prefix + "sys_user_role VALUES(7,1),(70,2),(71,3),(72,2),(73,4),(74,4),(75,1)");
                sql("INSERT INTO " + prefix + "product_model VALUES(4,'Model',1)");
                sql("INSERT INTO " + prefix + "sales_order VALUES(1,'SO-A',44,8),(2,'SO-B',45,99)");
                sql("INSERT INTO " + prefix + "vehicle VALUES(55,'F55',4,3,44,'SO-A'),(56,'F56',4,3,45,'SO-B')");
                sql("INSERT INTO " + prefix + "vehicle_part_binding VALUES(1,55,'M55','MOTOR',1)");
            } catch (Exception error) { close(); throw error; }
        }
        <T> T as(String type, Long agentId, Supplier<T> work, String... permissions) {
            login(type, agentId, permissions);
            try { return tx.execute(status -> work.get()); }
            finally { SecurityContextHolder.clearContext(); }
        }
        <T> T asUser(long userId, String type, Long agentId, Supplier<T> work, String... permissions) {
            login(type, agentId, permissions);
            var authentication = (org.springframework.security.authentication.UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
            authentication.setDetails(new com.bike.server.auth.UserAuthRecord(userId, "user-"+userId, "unused", type, agentId, null, true));
            try { return tx.execute(status -> work.get()); }
            finally { SecurityContextHolder.clearContext(); }
        }
        <T> T admin(Supplier<T> work) { return as("WEB_ADMIN", null, work, "ROLE_SUPER_ADMIN"); }
        <T> T agent(Supplier<T> work) { return as("AGENT", 8L, work, "ROLE_WEB_AGENT", "PERM_AFTER_SALES_READ", "PERM_AFTER_SALES_WRITE"); }
        long submittedCase() { long id = agent(() -> service.create(request(null, null, null))).getId(); agent(() -> service.submit(id)); return id; }
        void sql(String statement) throws Exception { try (var connection = source.getConnection(); var sql = connection.createStatement()) { sql.execute(statement); } }
        long count(String suffix) throws Exception {
            assertThat(suffix).matches("[a-z_]+");
            try (var connection = source.getConnection(); var sql = connection.createStatement(); var rs = sql.executeQuery("SELECT COUNT(*) FROM " + prefix + suffix)) { rs.next(); return rs.getLong(1); }
        }
        @Override public void close() throws Exception {
            // Shipment references plan items, while return rows reference shipment. Break this cycle only in the disposable fixture.
            if(tables.contains(prefix+"after_sales_solution_item"))sql("ALTER TABLE "+prefix+"after_sales_shipment DROP FOREIGN KEY fk_"+prefix+"after_sales_shipment_solution");
            var reverse = new ArrayList<>(tables); Collections.reverse(reverse);
            for (String table : reverse) { assertThat(table).startsWith(prefix).matches("[a-z0-9_]+"); sql("DROP TABLE " + table); }
        }
    }
}
