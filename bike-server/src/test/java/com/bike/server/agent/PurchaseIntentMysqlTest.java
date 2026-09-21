package com.bike.server.agent;

import com.bike.server.common.BusinessException;
import com.bike.server.common.OperationLogMapper;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 真实 MySQL、正式 Mapper 和 Service，两连接控制交错；唯一前缀表不触碰业务数据。 */
@EnabledIfEnvironmentVariable(named = "BIKE_MYSQL_TEST_URL", matches = ".+")
class PurchaseIntentMysqlTest {
    @Test void committedRevocationRejectsWaitingPurchase() throws Exception {
        try (var f = new Fixture(); var workers = Executors.newFixedThreadPool(2)) {
            var revoked = new CountDownLatch(1);
            var commitRevocation = new CountDownLatch(1);
            var purchaseStarted = new CountDownLatch(1);
            var purchases = f.purchaseProxy(purchaseStarted, null, null);
            Future<?> revoke = workers.submit(() -> f.transaction(() -> {
                f.access.replace(1, List.of());
                revoked.countDown();
                await(commitRevocation);
                return null;
            }));
            try {
                await(revoked);
                Future<String> purchase = workers.submit(() -> {
                    try {
                        f.transaction(() -> purchases.create(request("after-revoke")));
                        return "UNEXPECTED_SUCCESS";
                    } catch (BusinessException error) { return error.getCode(); }
                });
                await(purchaseStarted);
                assertThatThrownBy(() -> purchase.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
                commitRevocation.countDown();
                revoke.get(10, TimeUnit.SECONDS);
                assertThat(purchase.get(10, TimeUnit.SECONDS)).isEqualTo("MODEL_NOT_AUTHORIZED");
                assertThat(f.count()).isZero();
            } finally { commitRevocation.countDown(); }
        }
    }

    @Test void purchaseCommitsBeforeWaitingRevocationThenFurtherPurchasesFail() throws Exception {
        try (var f = new Fixture(); var workers = Executors.newFixedThreadPool(2)) {
            var inserted = new CountDownLatch(1);
            var commitPurchase = new CountDownLatch(1);
            var revokeStarted = new CountDownLatch(1);
            var purchases = f.purchaseProxy(null, inserted, commitPurchase);
            var guardedAccess = proxy(AgentModelAccessMapper.class, f.accessMapper, (method, before) -> {
                if (before && method.equals("lockAgent")) revokeStarted.countDown();
            });
            var revoker = new AgentModelAccessService(guardedAccess, mock(OperationLogMapper.class));
            Future<PurchaseIntentView> purchase = workers.submit(() ->
                f.transaction(() -> purchases.create(request("before-revoke"))));
            try {
                await(inserted);
                Future<?> revoke = workers.submit(() -> f.transaction(() -> revoker.replace(1, List.of())));
                await(revokeStarted);
                assertThatThrownBy(() -> revoke.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
                commitPurchase.countDown();
                assertThat(purchase.get(10, TimeUnit.SECONDS).remark()).isEqualTo("before-revoke");
                revoke.get(10, TimeUnit.SECONDS);
                assertThat(f.count()).isEqualTo(1);
                assertThatThrownBy(() -> f.transaction(() -> f.purchases.create(request("later"))))
                    .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("MODEL_NOT_AUTHORIZED"));
                assertThat(f.count()).isEqualTo(1);
            } finally { commitPurchase.countDown(); }
        }
    }

    @Test void concurrentRequestsReturnTheirOwnPersistedIdsAndValues() throws Exception {
        try (var f = new Fixture(); var workers = Executors.newFixedThreadPool(4)) {
            List<Future<PurchaseIntentView>> requests = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String remark = "request-" + i;
                requests.add(workers.submit(() -> f.transaction(() -> f.purchases.create(request(remark)))));
            }
            Set<Long> ids = new HashSet<>();
            for (int i = 0; i < requests.size(); i++) {
                var view = requests.get(i).get(10, TimeUnit.SECONDS);
                assertThat(view.remark()).isEqualTo("request-" + i);
                assertThat(ids.add(view.id())).isTrue();
                assertThat(f.purchaseMapper.selectById(view.id()).remark()).isEqualTo(view.remark());
            }
            assertThat(f.count()).isEqualTo(8);
        }
    }

    @Test void disabledAgentCannotSubmitEvenWithStoredGrant() throws Exception {
        try (var f = new Fixture()) {
            try (var connection = f.source.getConnection(); var sql = connection.createStatement()) {
                sql.executeUpdate("UPDATE " + f.prefix + "agent SET status='DISABLED'");
            }
            assertThatThrownBy(() -> f.transaction(() -> f.purchases.create(request("disabled"))))
                .isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getCode()).isEqualTo("AGENT_NOT_FOUND"));
            assertThat(f.count()).isZero();
        }
    }

    private static PurchaseIntentCreateRequest request(String remark) {
        return new PurchaseIntentCreateRequest(1L, 1L, 2, null, remark);
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).as("controlled transaction checkpoint").isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }

    private interface Checkpoint { void visit(String method, boolean before); }

    private static <T> T proxy(Class<T> type, T delegate, Checkpoint checkpoint) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            checkpoint.visit(method.getName(), true);
            try {
                Object result = method.invoke(delegate, args);
                checkpoint.visit(method.getName(), false);
                return result;
            } catch (InvocationTargetException error) { throw error.getCause(); }
        }));
    }

    private static final class Fixture implements AutoCloseable {
        final String prefix = "accept_pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_";
        final UnpooledDataSource source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
            System.getenv("BIKE_MYSQL_TEST_URL"), System.getenv("BIKE_MYSQL_TEST_USER"), System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
        final List<String> tables = new ArrayList<>();
        final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        final PurchaseIntentMapper purchaseMapper;
        final AgentModelAccessMapper accessMapper;
        final PurchaseIntentService purchases;
        final AgentModelAccessService access;

        Fixture() throws Exception {
            var config = new Configuration(new Environment("purchase-concurrency", new SpringManagedTransactionFactory(), source));
            config.setMapUnderscoreToCamelCase(true);
            for (String name : List.of("AgentModelAccessMapper", "PurchaseIntentMapper")) {
                try (var input = Resources.getResourceAsStream("mapper/" + name + ".xml")) {
                    String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", prefix);
                    new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                        config, name, config.getSqlFragments()).parse();
                }
            }
            var session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            purchaseMapper = session.getMapper(PurchaseIntentMapper.class);
            accessMapper = session.getMapper(AgentModelAccessMapper.class);
            purchases = new PurchaseIntentServiceImpl(purchaseMapper);
            access = new AgentModelAccessService(accessMapper, mock(OperationLogMapper.class));
            try (var connection = source.getConnection(); var sql = connection.createStatement()) {
                for (String ddl : List.of(
                    "agent(id BIGINT PRIMARY KEY,agent_name VARCHAR(30),status VARCHAR(20))",
                    "product_model(id BIGINT PRIMARY KEY,model_name VARCHAR(30),enabled INT)",
                    "agent_model_access(agent_id BIGINT,model_id BIGINT,enabled INT,PRIMARY KEY(agent_id,model_id))",
                    "agent_purchase_intent(id BIGINT AUTO_INCREMENT PRIMARY KEY,agent_id BIGINT,product_model_id BIGINT,intent_qty INT,status VARCHAR(20),expire_time DATETIME,remark VARCHAR(500),created_at DATETIME DEFAULT CURRENT_TIMESTAMP,created_by BIGINT)")) {
                    String table = prefix + ddl.substring(0, ddl.indexOf('('));
                    sql.execute("CREATE TABLE " + prefix + ddl + " ENGINE=InnoDB");
                    tables.add(table);
                }
                sql.execute("INSERT INTO " + prefix + "agent VALUES(1,'A','ENABLED')");
                sql.execute("INSERT INTO " + prefix + "product_model VALUES(1,'M',1)");
                sql.execute("INSERT INTO " + prefix + "agent_model_access VALUES(1,1,1)");
            } catch (Exception error) { close(); throw error; }
        }

        PurchaseIntentService purchaseProxy(CountDownLatch started, CountDownLatch inserted, CountDownLatch commit) {
            return new PurchaseIntentServiceImpl(proxy(PurchaseIntentMapper.class, purchaseMapper, (method, before) -> {
                if (before && method.equals("lockAgentAccess") && started != null) started.countDown();
                if (!before && method.equals("insert") && inserted != null) { inserted.countDown(); await(commit); }
            }));
        }

        <T> T transaction(Supplier<T> action) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-admin", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
            try { return tx.execute(status -> action.get()); }
            finally { SecurityContextHolder.clearContext(); }
        }

        long count() { return purchaseMapper.count(1L); }

        @Override public void close() throws Exception {
            try (var connection = source.getConnection(); var sql = connection.createStatement()) {
                for (String table : tables) {
                    assertThat(table).startsWith(prefix).matches("[a-z0-9_]+");
                    sql.execute("DROP TABLE " + table);
                }
            }
        }
    }
}
