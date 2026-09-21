package com.bike.server.stock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.*;
import com.bike.server.lock.*;
import com.bike.server.order.*;
import com.bike.server.pi.PiMapper;
import com.bike.server.production.*;
import com.bike.server.system.SystemConfigMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.io.ByteArrayInputStream;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Real service transactions and shared database mutex; isolated tables are always removed. */
@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class SkuCommitmentMysqlTest {
    @Test void skuLockIsIdempotentAndBlueOrUnidentifiedStockCannotReplaceRed() throws Exception {
        try(var f=new Fixture()) {
            var first=f.tx(()->f.locks.create(request("same-request",10,1)));
            assertThat(first).hasSize(1);
            assertThat(first.getFirst().getScopeType()).isEqualTo("SKU");
            assertThat(first.getFirst().getAgentId()).isNull();
            assertThat(first.getFirst().getCustomerId()).isEqualTo(1L);
            assertThat(f.tx(()->f.locks.create(request("same-request",10,1))).getFirst().getId()).isEqualTo(first.getFirst().getId());
            code(()->f.tx(()->f.locks.create(request("same-request",11,1))),"SKU_LOCK_REQUEST_CONFLICT");
            code(()->f.tx(()->f.locks.create(request("red-again",10,1))),"SKU_INVENTORY_NOT_ENOUGH");
            var red=f.inventory.list(null,null,0,100).stream().filter(r->r.skuId()==10).findFirst().orElseThrow();
            assertThat(red.physicalQty()).isEqualTo(1);
            assertThat(red.temporaryLockedQty()).isEqualTo(1);
            assertThat(red.maxNewLockQty()).isZero();
            assertThat(f.reservations.snapshot(4,0).getCommittedQty()).isEqualTo(1);
            assertThat(f.inventory.snapshot(11,0).availableForOrder()).isEqualTo(1);
            assertThat(f.scalar("SELECT COUNT(*) FROM @sku_lock_request")).isEqualTo(1);
        }
    }

    @Test void twoConcurrentRequestsForLastRedProduceExactlyOneCommitment() throws Exception {
        try(var f=new Fixture();var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            List<Future<String>> futures=new ArrayList<>();
            for(int n=0;n<2;n++) { final int id=n; futures.add(pool.submit(()-> {
                if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("start timed out");
                try { f.tx(()->f.locks.create(request("race-request-"+id,10,1)));return "OK"; }
                catch(BusinessException error) {return error.getCode();}
            })); }
            start.countDown();
            List<String> results=new ArrayList<>();
            for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("OK","SKU_INVENTORY_NOT_ENOUGH");
            assertThat(f.reservations.snapshot(4,0).getCommittedQty()).isEqualTo(1);
            assertThat(f.scalar("SELECT COUNT(*) FROM @inventory_lock")).isEqualTo(1);
        }
    }

    @Test void batchFailureRollsBackEarlierSkuAndIdempotencyRecord() throws Exception {
        try(var f=new Fixture()) {
            var batch=new SkuLockRequest("batch-fails",1L,null,null,null,List.of(new SkuLockRequest.Item(10,1),new SkuLockRequest.Item(12,1)));
            code(()->f.tx(()->f.locks.create(batch)),"SKU_INVENTORY_NOT_ENOUGH");
            assertThat(f.scalar("SELECT COUNT(*) FROM @inventory_lock")).isZero();
            assertThat(f.scalar("SELECT COUNT(*) FROM @sku_lock_request")).isZero();
            assertThat(f.scalar("SELECT COUNT(*) FROM @operation_log")).isZero();
        }
    }

    @Test void legacyModelCommitmentAndSkuCommitmentShareOneModelBudget() throws Exception {
        try(var f=new Fixture()) {
            f.legacyLock(3);
            code(()->f.tx(()->f.locks.create(request("blocked-by-legacy",10,1))),"INVENTORY_NOT_ENOUGH");
            assertThat(f.inventory.list(4L,null,0,100)).allSatisfy(r->{
                assertThat(r.legacyModelCommittedQty()).isEqualTo(3);
                assertThat(r.maxNewLockQty()).isZero();
            });
            f.exec("UPDATE @inventory_lock SET lock_qty=2");
            f.tx(()->f.locks.create(request("red-after-release",10,1)));
            assertThat(f.reservations.snapshot(4,0).getCommittedQty()).isEqualTo(3);
            code(()->f.tx(()->f.locks.create(request("blue-blocked",11,1))),"INVENTORY_NOT_ENOUGH");
        }
    }

    @Test void conversionPreservesSkuDeadlineAndCommitmentAndEnforcesCustomer() throws Exception {
        try(var f=new Fixture()) {
            var lock=f.tx(()->f.locks.create(request("convert-red",10,1))).getFirst();
            code(()->f.tx(()->f.conversion.convert(lock.getId(),new LockOrderRequest(2L,1,"wrong-customer",null))),"LOCK_CUSTOMER_MISMATCH");
            var conversion=new LockOrderRequest(1L,1,"convert-request",null);
            var order=f.tx(()->f.conversion.convert(lock.getId(),conversion));
            assertThat(f.tx(()->f.conversion.convert(lock.getId(),conversion)).getId()).isEqualTo(order.getId());
            assertThat(order.getAgentId()).isNull();
            assertThat(f.reservations.expiry(order.getId())).isEqualTo(lock.getExpireTime());
            assertThat(f.reservations.snapshot(4,order.getId()).getCommittedQty()).isEqualTo(1);
            assertThat(f.inventory.reservationItems(order.getId())).containsExactly(new SkuReservationItem(10,1,0,0));
            f.tx(()->f.orders.updateStatus(order.getId(),"CONFIRMED"));
            code(()->f.tx(()->f.stock.stockOut(new StockOutRequest(1L,order.getId(),List.of("QR101")))),"ORDER_SKU_MISMATCH");
            var record=f.tx(()->f.stock.stockOut(new StockOutRequest(1L,order.getId(),List.of("QR100"))));
            assertThat(f.inventory.reservationItems(order.getId()).getFirst().shippedQty()).isEqualTo(1);
            assertThat(f.reservations.snapshot(4,0).getCommittedQty()).isZero();
            f.tx(()->f.stock.cancelStockOut(record.getId(),"重新核对"));
            assertThat(f.inventory.reservationItems(order.getId()).getFirst().shippedQty()).isZero();
            assertThat(f.reservations.snapshot(4,0).getCommittedQty()).isEqualTo(1);
            code(()->f.tx(()->f.stock.cancelStockIn(100,"不能拿走已承诺红车")),"SKU_INVENTORY_LOCK_CONFLICT");
        }
    }

    @Test void legacyOrderCannotConsumeAnotherSkuHoldAndLastWrongVehicleRollsBackWholeBatch() throws Exception {
        try(var f=new Fixture()) {
            f.tx(()->f.locks.create(request("red-held",10,1)));
            long order=f.legacyOrder(2);
            code(()->f.tx(()->f.stock.stockOut(new StockOutRequest(1L,order,List.of("QR101","QR100")))),"SKU_INVENTORY_LOCK_CONFLICT");
            assertThat(f.scalar("SELECT COUNT(*) FROM @stock_out_record")).isZero();
            assertThat(f.scalar("SELECT COUNT(*) FROM @vehicle WHERE status=2")).isEqualTo(3);
            assertThat(f.orders.selectById(order).getShippedQty()).isZero();
            var free=f.tx(()->f.stock.stockOut(new StockOutRequest(1L,order,List.of("QR101","QR102"))));
            assertThat(free).isNotNull();
            assertThat(f.reservations.snapshot(4,0).getPhysicalQty()).isEqualTo(1);
            assertThat(f.inventory.snapshot(10,0).availableForOrder()).isZero();
        }
    }

    @Test void expiredReacquisitionChecksExactSkuAndPaidModeDoesNotLoseItsHold() throws Exception {
        try(var f=new Fixture()) {
            var order=f.skuOrder(1);
            f.exec("UPDATE @order_reservation SET expires_at=DATE_SUB(NOW(3),INTERVAL 1 HOUR)");
            f.tx(()->f.locks.create(request("someone-else-red",10,1)));
            code(()->f.tx(()->f.renew.renew(order.getId(),48,"显式重新争取")),"SKU_INVENTORY_NOT_ENOUGH");
            assertThat(f.reservations.expiredForOrder(order.getId())).isEqualTo(1);
            f.exec("UPDATE @inventory_lock SET status='RELEASED' WHERE status='LOCKED'");
            f.tx(()->f.renew.renew(order.getId(),48,"红车已释放"));
            assertThat(f.reservations.expiredForOrder(order.getId())).isZero();
            f.exec("UPDATE @order_reservation SET hold_mode='PAID',expires_at=DATE_SUB(NOW(3),INTERVAL 1 HOUR)");
            f.tx(()->f.renew.renew(order.getId(),1,"付款保留不能降级"));
            assertThat(f.tx(()->f.reservations.selectForUpdate(order.getId())).holdMode()).isEqualTo("PAID");
            assertThat(f.inventory.snapshot(10,order.getId()).ownedQty()).isEqualTo(1);
        }
    }

    @Test void quarantineProducesSkuShortageWithoutReleasingOrReplacingCommitment() throws Exception {
        try(var f=new Fixture()) {
            f.tx(()->f.locks.create(request("quarantine-red",10,1)));
            f.exec("INSERT INTO @production_issue(code,status) VALUES('F100','OPEN')");
            var red=f.inventory.list(4L,null,0,100).stream().filter(r->r.skuId()==10).findFirst().orElseThrow();
            assertThat(red.shortageQty()).isEqualTo(1);
            assertThat(red.availableQty()).isZero();
            assertThat(red.temporaryLockedQty()).isEqualTo(1);
            assertThat(f.inventory.snapshot(11,0).availableForOrder()).isEqualTo(1);
            code(()->f.tx(()->f.locks.create(request("red-shortage",10,1))),"SKU_INVENTORY_NOT_ENOUGH");
        }
    }

    @Test void agentInventoryHidesUnauthorizedModelsAndRevokedAuthorizationImmediately() throws Exception {
        try(var f=new Fixture()) {
            assertThat(f.inventory.list(null,9L,0,100)).extracting(SkuInventoryRow::skuId).containsExactly(10L,11L);
            assertThat(f.inventory.list(5L,9L,0,100)).isEmpty();
            f.exec("UPDATE @agent_model_access SET enabled=0");
            assertThat(f.inventory.list(null,9L,0,100)).isEmpty();
            assertThat(f.inventory.count(null,9L)).isZero();
        }
    }

    @Test void modelSkuShortageSurvivesBlueSurplusWarehouseFiltersAndSummaryDeduplication() throws Exception {
        try(var f=new Fixture()) {
            f.addVehicle(103,11);
            f.tx(()->f.locks.create(request("red-structural-shortage",10,1)));
            f.legacyLock(1);
            f.exec("INSERT INTO @warehouse VALUES(2,'库房二','ENABLED')");
            f.exec("INSERT INTO @warehouse_location VALUES(2,2,'库位二','ENABLED')");
            f.exec("UPDATE @vehicle SET current_warehouse_id=2,current_location_id=2 WHERE id IN(101,102,103)");
            f.exec("INSERT INTO @production_issue(code,status) VALUES('F100','OPEN')");
            var locations=f.stockMapper.selectInventory(4L,null,null,0,100);
            assertThat(locations).hasSize(2).allSatisfy(row->{
                assertThat(row.getSkuShortageQty()).isEqualTo(1);
                assertThat(row.getLegacyModelCommittedQty()).isEqualTo(1);
                assertThat(row.getModelNewCommitmentQty()).isEqualTo(1);
            });
            // A warehouse containing no red vehicles still displays the model-wide red deficit.
            assertThat(f.stockMapper.selectInventory(4L,2L,9L,0,100)).hasSize(1)
                    .allSatisfy(row->assertThat(row.getSkuShortageQty()).isEqualTo(1));
            var summary=f.stockMapper.selectInventorySummary();
            assertThat(number(summary,"skuShortageQty")).isEqualTo(1);
            assertThat(number(summary,"legacyModelCommittedQty")).isEqualTo(1);
            assertThat(number(summary,"modelNewCommitmentQty")).isEqualTo(1);
            assertThat(number(summary,"lockedQty")).isEqualTo(2);
            assertThat(number(summary,"shortageQty")).isZero();
            assertThat(f.dashboard.selectInventorySummary()).isEqualTo(summary);
            var page=f.tx(()->new com.bike.server.module.ModuleServiceImpl(f.modules).list("inventory"));
            assertThat(page.columns()).endsWith("车型SKU缺口（不累计）","历史车型承诺（不累计）","车型可新增上限（不累计）");
            assertThat(page.rows()).hasSize(2).allSatisfy(row->assertThat(row.subList(12,15)).containsExactly("1","1","1"));
            assertThat(f.modules.selectRows("agent-inventory",9L,false)).hasSize(2)
                    .allSatisfy(row->assertThat(number(row,"车型SKU缺口（不累计）")).isEqualTo(1));
            var blue=f.inventory.list(4L,null,0,100).stream().filter(row->row.skuId()==11).findFirst().orElseThrow();
            assertThat(blue.availableQty()).isEqualTo(2);assertThat(blue.maxNewLockQty()).isEqualTo(1);
            f.exec("UPDATE @agent_model_access SET enabled=0");
            assertThat(f.stockMapper.selectInventory(null,null,9L,0,100)).isEmpty();
            assertThat(f.modules.selectRows("agent-inventory",9L,false)).isEmpty();
        }
    }

    private static long number(Map<String,Object> values,String key) {return ((Number)values.get(key)).longValue();}

    @Test void closedRemainderIsExplicitAndCannotBeRestored() throws Exception {
        try(var f=new Fixture()) {
            f.addVehicle(103,10);
            var order=f.skuOrder(2);
            f.tx(()->f.orders.updateStatus(order.getId(),"CONFIRMED"));
            var out=f.tx(()->f.stock.stockOut(new StockOutRequest(1L,order.getId(),List.of("QR100"))));
            f.tx(()->f.renew.closeRemainder(order.getId(),"剩余一台取消"));
            assertThat(f.inventory.reservationItems(order.getId())).containsExactly(new SkuReservationItem(10,2,1,1));
            assertThat(f.orders.selectById(order.getId()).getShippedQty()).isEqualTo(1);
            assertThat(f.reservations.snapshot(4,0).getCommittedQty()).isZero();
            code(()->f.tx(()->f.stock.cancelStockOut(out.getId(),"不能重开")),"INVALID_STATUS_TRANSITION");
        }
    }

    private static SkuLockRequest request(String key,long sku,int qty) {
        return new SkuLockRequest(key,1L,null,null,null,List.of(new SkuLockRequest.Item(sku,qty)));
    }
    private static void code(Supplier<?> action,String expected) {
        assertThatThrownBy(action::get).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(expected));
    }

    static final class Fixture implements AutoCloseable {
        final String prefix="accept_sku_"+UUID.randomUUID().toString().replace("-","").substring(0,10)+"_";
        final UnpooledDataSource source=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",System.getenv("BIKE_MYSQL_TEST_URL"),System.getenv("BIKE_MYSQL_TEST_USER"),System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
        final TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        final JdbcTemplate jdbc=new JdbcTemplate(source);
        final List<String> tables=new ArrayList<>();
        final jakarta.validation.ValidatorFactory validation=Validation.buildDefaultValidatorFactory();
        final SkuInventoryMapper inventory;
        final OrderReservationMapper reservations;
        final OrderMapper orders;
        final InventoryLockMapper lockMapper;
        final StockMapper stockMapper;
        final com.bike.server.module.ModuleMapper modules;
        final com.bike.server.dashboard.DashboardMapper dashboard;
        final ProductionGuard guard;
        final SkuLockService locks;
        final LockOrderService conversion;
        final StockServiceImpl stock;
        final OrderReservationService renew;
        com.bike.server.pi.PiServiceImpl pis;
        com.bike.server.pi.PiConversionService piConversion;
        com.bike.server.pi.PiSeriesMapper piSeries;
        com.bike.server.pi.PiDispatchService dispatch;
        com.bike.server.pi.PiDispatchMapper dispatchMapper;
        com.bike.server.pi.PiLogisticsMapper logisticsMapper;
        com.bike.server.pi.PiLogisticsService logistics;

        Fixture() throws Exception { this(false); }
        Fixture(boolean withPi) throws Exception {
            var config=new Configuration(new Environment("sku-commitment",new SpringManagedTransactionFactory(),source));
            config.setMapUnderscoreToCamelCase(true);
            for(String name:List.of("InventoryPolicyMapper","AuditPolicyMapper","AgentModelAccessMapper","InventoryLockMapper","SkuLockMapper","SkuInventoryMapper","OrderMapper","OrderReservationMapper","ProductionMapper","VehicleMapper","StockMapper","OperationLogMapper","ModuleMapper","DashboardMapper","PiMapper","PiSeriesMapper","PiShipmentMapper","PiDispatchMapper","PiLogisticsMapper"))
                try(var input=Resources.getResourceAsStream("mapper/"+name+".xml")) {
                    var xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix);
                    new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),config,name,config.getSqlFragments()).parse();
                }
            var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            inventory=session.getMapper(SkuInventoryMapper.class);reservations=session.getMapper(OrderReservationMapper.class);
            orders=session.getMapper(OrderMapper.class);lockMapper=session.getMapper(InventoryLockMapper.class);
            stockMapper=session.getMapper(StockMapper.class);modules=session.getMapper(com.bike.server.module.ModuleMapper.class);
            dashboard=session.getMapper(com.bike.server.dashboard.DashboardMapper.class);
            guard=new ProductionGuard(session.getMapper(ProductionMapper.class));
            var json=new ObjectMapper().findAndRegisterModules();
            locks=new SkuLockService(session.getMapper(SkuLockMapper.class),lockMapper,inventory,reservations,orders,guard,mock(SystemConfigMapper.class),json,validation.getValidator());
            conversion=new LockOrderService(lockMapper,orders,reservations,guard);
            ReflectionTestUtils.setField(conversion,"skuInventory",inventory);
            var skuGuard=new SkuInventoryGuard(inventory);
            var inventoryGuard=new OrderInventoryGuard(reservations);
            ReflectionTestUtils.setField(inventoryGuard,"skuInventoryGuard",skuGuard);
            stock=new StockServiceImpl(stockMapper,orders);
            ReflectionTestUtils.setField(stock,"productionGuard",guard);
            ReflectionTestUtils.setField(stock,"inventoryGuard",inventoryGuard);
            var coordinator=new OrderReservationCoordinator(guard,reservations,session.getMapper(OperationLogMapper.class),json);
            ReflectionTestUtils.setField(coordinator,"skuInventory",inventory);
            renew=new OrderReservationService(orders,reservations,coordinator,mock(PiMapper.class));
            ReflectionTestUtils.setField(renew,"skuInventoryGuard",skuGuard);
            try {
                for(String ddl:List.of(
                    "customer(id BIGINT PRIMARY KEY,customer_code VARCHAR(64),customer_name VARCHAR(100),status VARCHAR(32))",
                    "agent(id BIGINT PRIMARY KEY,agent_name VARCHAR(100),status VARCHAR(32))",
                    "agent_model_access(agent_id BIGINT,model_id BIGINT,enabled INT)",
                    "product_model(id BIGINT PRIMARY KEY,model_name VARCHAR(100),enabled INT)",
                    "production_sku(id BIGINT PRIMARY KEY,model_id BIGINT,sku_code VARCHAR(64),product_name VARCHAR(100),specification VARCHAR(100),enabled INT)",
                    "sales_order(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(64),customer_id BIGINT,agent_id BIGINT,status VARCHAR(32),planned_ship_date DATE,remark VARCHAR(500),created_by BIGINT,created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3))",
                    "sales_order_item(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT,product_model_id BIGINT,ordered_qty INT,shipped_qty INT DEFAULT 0)",
                    "production_mutex(id INT PRIMARY KEY)",
                    "inventory_lock(id BIGINT AUTO_INCREMENT PRIMARY KEY,lock_no VARCHAR(64),agent_id BIGINT NOT NULL,product_model_id BIGINT,lock_qty INT,released_qty INT DEFAULT 0,converted_qty INT DEFAULT 0,status VARCHAR(32),expire_time DATETIME(3),created_by BIGINT,created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),released_at DATETIME(3),remark VARCHAR(500))",
                    "inventory_lock_detail(id BIGINT AUTO_INCREMENT PRIMARY KEY,lock_id BIGINT,product_model_id BIGINT,lock_qty INT)",
                    "order_reservation(order_id BIGINT PRIMARY KEY,source_lock_id BIGINT,request_id VARCHAR(64),model_id BIGINT,reserved_qty INT,expires_at DATETIME(3),expiry_audited_at DATETIME(3),hold_mode VARCHAR(16) DEFAULT 'TIMED',payment_hold_pi_id BIGINT,payment_held_at DATETIME(3),payment_held_by BIGINT,payment_held_amount DECIMAL(18,2),released_at DATETIME(3))",
                    "vehicle(id BIGINT PRIMARY KEY,model_id BIGINT,status INT,frame_sn VARCHAR(64),vehicle_qr_code VARCHAR(100),vehicle_qr_token VARCHAR(100),qr_generated_at DATETIME(3),qr_version INT,assemble_operator_id BIGINT,assemble_time DATETIME(3),current_warehouse_id BIGINT,current_location_id BIGINT,stock_in_time DATETIME(3),stock_out_time DATETIME(3),current_customer_id BIGINT,current_order_no VARCHAR(64),created_at DATETIME(3),updated_at DATETIME(3))",
                    "production_vehicle(vehicle_id BIGINT PRIMARY KEY,sku_id BIGINT,batch_no VARCHAR(64))",
                    "production_issue(code VARCHAR(64),status VARCHAR(32))",
                    "vehicle_part_binding(vehicle_id BIGINT,part_type VARCHAR(32),part_sn VARCHAR(64),is_current INT)",
                    "warehouse(id BIGINT PRIMARY KEY,warehouse_name VARCHAR(100),status VARCHAR(32))",
                    "warehouse_location(id BIGINT PRIMARY KEY,warehouse_id BIGINT,location_name VARCHAR(100),status VARCHAR(32))",
                    "sys_user(id BIGINT PRIMARY KEY,display_name VARCHAR(100))",
                    "stock_in_record(id BIGINT AUTO_INCREMENT PRIMARY KEY,stock_in_no VARCHAR(64),vehicle_id BIGINT,warehouse_id BIGINT,location_id BIGINT,operator_id BIGINT,status VARCHAR(32),operation_time DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),cancel_reason VARCHAR(500),cancelled_by BIGINT,cancelled_at DATETIME(3))",
                    "stock_out_record(id BIGINT AUTO_INCREMENT PRIMARY KEY,stock_out_no VARCHAR(64),vehicle_id BIGINT,customer_id BIGINT,order_id BIGINT,operator_id BIGINT,status VARCHAR(32),operation_time DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),cancel_reason VARCHAR(500),cancelled_by BIGINT,cancelled_at DATETIME(3))",
                    "sales_order_vehicle(order_id BIGINT,vehicle_id BIGINT,stock_out_record_id BIGINT)",
                    "operation_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,business_type VARCHAR(64),business_id VARCHAR(64),action VARCHAR(64),frame_sn VARCHAR(64),operator_id BIGINT,operator_name VARCHAR(100),before_data JSON,after_data JSON,client_ip VARCHAR(64),remark VARCHAR(500))")) {
                    exec("CREATE TABLE @"+ddl+" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                    tables.add(ddl.substring(0,ddl.indexOf('(')));
                }
                try(var input=Resources.getResourceAsStream("db/migration/V28__sku_inventory_commitments.sql")) {
                    String ddl=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix)
                            .replaceAll("CONSTRAINT ([a-z_]+)","CONSTRAINT "+prefix+"$1");
                    for(String statement:ddl.split(";")) {
                        String clean=statement.replaceAll("(?m)^--.*$","").trim();
                        if(clean.isBlank())continue;
                        jdbc.execute(clean);
                        if(clean.startsWith("CREATE TABLE"))tables.add(clean.split("[ (\\r\\n]+",4)[2].substring(prefix.length()));
                    }
                }
                exec("INSERT INTO @production_mutex VALUES(1)");
                exec("INSERT INTO @customer VALUES(1,'C1','客户1','ENABLED'),(2,'C2','客户2','ENABLED')");
                exec("INSERT INTO @agent VALUES(9,'代理商','ENABLED')");
                exec("INSERT INTO @agent_model_access VALUES(9,4,1)");
                exec("INSERT INTO @product_model VALUES(4,'城市车',1),(5,'山地车',1)");
                exec("INSERT INTO @production_sku VALUES(10,4,'RED','红车','红',1),(11,4,'BLUE','蓝车','蓝',1),(12,5,'GREEN','绿车','绿',1)");
                exec("INSERT INTO @warehouse VALUES(1,'库房','ENABLED')");
                exec("INSERT INTO @warehouse_location VALUES(1,1,'库位','ENABLED')");
                addVehicle(100,10);addVehicle(101,11);addVehicle(102,null);
                if(withPi) {
                    for(String file:List.of("V24__pi_multi_item_and_fulfillment.sql","V30__pi_series_and_atomic_conversion.sql","V31__pi_dispatch_batches.sql","V32__pi_logistics_actual_batches.sql","V33__pi_revisions_and_series_payments.sql")) {
                        try(var input=Resources.getResourceAsStream("db/migration/"+file)) {
                            String ddl=new String(input.readAllBytes(),StandardCharsets.UTF_8).split("INSERT INTO bike_sys_permission")[0]
                                    .replace("bike_",prefix).replaceAll("CONSTRAINT (fk_(?:pi|dispatch|logistics)_[a-z_]+)","CONSTRAINT "+prefix+"$1");
                            for(String statement:ddl.split(";")) {
                                String clean=statement.replaceAll("(?m)^--.*$","").trim();if(clean.isBlank())continue;jdbc.execute(clean);
                                if(clean.startsWith("CREATE TABLE"))tables.add(clean.replaceFirst("CREATE TABLE (IF NOT EXISTS )?","").split("[ (\\r\\n]",2)[0].substring(prefix.length()));
                            }
                        }
                    }
                    var mapper=session.getMapper(com.bike.server.pi.PiMapper.class);
                    piSeries=session.getMapper(com.bike.server.pi.PiSeriesMapper.class);
                    ReflectionTestUtils.setField(coordinator,"series",piSeries);
                    pis=new com.bike.server.pi.PiServiceImpl(mapper,mock(com.bike.server.pi.PiDocumentService.class),session.getMapper(OperationLogMapper.class),json,validation.getValidator(),coordinator);
                    ReflectionTestUtils.setField(pis,"series",piSeries);ReflectionTestUtils.setField(pis,"skuInventory",inventory);
                    ReflectionTestUtils.setField(pis,"fulfillmentOrders",orders);ReflectionTestUtils.setField(pis,"orderInventoryGuard",inventoryGuard);
                    piConversion=new com.bike.server.pi.PiConversionService(lockMapper,conversion,pis,piSeries,guard,json,validation.getValidator());
                    var gate=new com.bike.server.pi.PiShipmentGuard(session.getMapper(com.bike.server.pi.PiShipmentMapper.class));
                    ReflectionTestUtils.setField(gate,"series",piSeries);ReflectionTestUtils.setField(stock,"piShipmentGuard",gate);
                    dispatchMapper=session.getMapper(com.bike.server.pi.PiDispatchMapper.class);
                    var recorder=new com.bike.server.pi.PiDispatchRecorder(dispatchMapper);
                    ReflectionTestUtils.setField(stock,"dispatchRecorder",recorder);
                    dispatch=new com.bike.server.pi.PiDispatchService(dispatchMapper,piSeries,recorder,stock,orders,guard,session.getMapper(OperationLogMapper.class),validation.getValidator(),json);
                    logisticsMapper=session.getMapper(com.bike.server.pi.PiLogisticsMapper.class);
                    logistics=new com.bike.server.pi.PiLogisticsService(logisticsMapper,mapper,piSeries,dispatchMapper,orders,guard,session.getMapper(OperationLogMapper.class),validation.getValidator(),json);
                    ReflectionTestUtils.setField(stock,"logistics",logisticsMapper);
                    ReflectionTestUtils.setField(pis,"logisticsMapper",logisticsMapper);ReflectionTestUtils.setField(pis,"logisticsService",logistics);
                }
            } catch(Exception error) {close();throw error;}
        }
        void addVehicle(long id,Integer sku) {
            exec("INSERT INTO @vehicle(id,model_id,status,frame_sn,vehicle_qr_code,current_warehouse_id,current_location_id,stock_in_time) VALUES("+id+",4,2,'F"+id+"','QR"+id+"',1,1,NOW(3))");
            if(sku!=null)exec("INSERT INTO @production_vehicle VALUES("+id+","+sku+",'batch')");
            exec("INSERT INTO @stock_in_record(id,stock_in_no,vehicle_id,warehouse_id,location_id,status) VALUES("+id+",'IN"+id+"',"+id+",1,1,'COMPLETED')");
        }
        void legacyLock(int qty) {exec("INSERT INTO @inventory_lock(lock_no,agent_id,product_model_id,lock_qty,status,expire_time) VALUES('LEGACY',9,4,"+qty+",'LOCKED',DATE_ADD(NOW(3),INTERVAL 1 DAY))");}
        long legacyOrder(int qty) {
            return tx(()-> {var request=new SalesOrderCreateRequest(1L,null,4L,qty,null,null);
                orders.insertOrder("LEGACY-ORDER",request,1L);orders.insertItem("LEGACY-ORDER",request);
                var order=orders.selectByNo("LEGACY-ORDER");orders.updateStatus(order.getId(),"CONFIRMED");return order.getId();});
        }
        SalesOrderView skuOrder(int qty) {
            var lock=tx(()->locks.create(request("sku-order",10,qty))).getFirst();
            return tx(()->conversion.convert(lock.getId(),new LockOrderRequest(1L,qty,"sku-order-conversion",null)));
        }
        <T>T tx(Supplier<T> work) {
            var auth=new UsernamePasswordAuthenticationToken("sku-tester",null,List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
            auth.setDetails(new UserAuthRecord(1,"sku-tester","","WEB_INTERNAL",null,null,true));
            SecurityContextHolder.getContext().setAuthentication(auth);
            try{return transaction.execute(status->work.get());}finally{SecurityContextHolder.clearContext();}
        }
        void exec(String sql) {jdbc.execute(sql.replace("@",prefix));}
        long scalar(String sql) {return jdbc.queryForObject(sql.replace("@",prefix),Long.class);}
        @Override public void close() {
            validation.close();
            if(tables.stream().anyMatch(t -> t.equals("pi_series"))) exec("ALTER TABLE @pi DROP FOREIGN KEY "+prefix+"fk_pi_series");
            // Foreign keys from inventory_lock to sku_lock_request require this deliberate order.
            List<String> removal=new ArrayList<>(tables.reversed());
            if(removal.contains("stock_out_batch")&&removal.remove("pi_logistics")) removal.add(removal.indexOf("stock_out_batch"),"pi_logistics");
            if(removal.remove("inventory_lock"))removal.add(removal.contains("sku_lock_request")?removal.indexOf("sku_lock_request"):0,"inventory_lock");
            for(String table:removal){assertThat(table).matches("[a-z0-9_]+");exec("DROP TABLE IF EXISTS @"+table);}
        }
    }
}
