package com.bike.server.stock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.*;
import com.bike.server.master.*;
import com.bike.server.order.*;
import com.bike.server.pi.*;
import com.bike.server.system.CustomerScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class CustomerScopeMysqlTest {
    static final String[] SALES={"PERM_PI_READ","PERM_PI_WRITE","PERM_ORDER_READ","PERM_ORDER_WRITE","PERM_AGENT_LOCK_WRITE"};
    static void login(long id,String type,String... grants) {
        var auth=new UsernamePasswordAuthenticationToken("scope-"+id,null,Arrays.stream(grants).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new UserAuthRecord(id,"scope-"+id,"",type,null,null,true));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    static PiCreateRequest request(long customer) {
        return new PiCreateRequest(customer,null,null,"DOMESTIC","CNY",null,null,null,null,BigDecimal.ZERO,BigDecimal.ZERO,null,null,
                List.of(new PiItemRequest(10L,1,BigDecimal.TEN,null,null)));
    }
    static void denied(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("CUSTOMER_SCOPE_DENIED"));
    }
    static class Fixture implements AutoCloseable {
        final SkuCommitmentMysqlTest.Fixture f=new SkuCommitmentMysqlTest.Fixture(true);
        final CustomerScopeMapper mapper;
        final CustomerScopeAccess access;
        final CustomerScopeService management;
        final OperationLogMapper audit=mock(OperationLogMapper.class);
        final MasterMapper masters;
        final com.bike.server.trace.TraceMapper trace;
        final OrderServiceImpl orders;
        Fixture()throws Exception {
            f.exec("ALTER TABLE @sys_user ADD username VARCHAR(100),ADD user_type VARCHAR(32) NOT NULL DEFAULT 'WEB_OPERATOR',ADD enabled TINYINT NOT NULL DEFAULT 1,ADD customer_scope_version BIGINT NOT NULL DEFAULT 0");
            f.exec("INSERT INTO @sys_user(id,username,display_name) VALUES(1,'sales-a','销售甲'),(2,'sales-b','销售乙'),(3,'manager','范围管理员')");
            f.exec("ALTER TABLE @operation_log ADD operation_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)");
            f.exec("ALTER TABLE @agent ADD agent_code VARCHAR(64)");
            f.exec("ALTER TABLE @customer ADD contact_name VARCHAR(100),ADD contact_phone VARCHAR(64),ADD created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),ADD updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)");
            try(var input=Resources.getResourceAsStream("db/migration-prod/V45__internal_customer_scope.sql")) {
                var ddl=new String(input.readAllBytes(),StandardCharsets.UTF_8);
                ddl=ddl.substring(ddl.indexOf("CREATE TABLE"),ddl.indexOf("INSERT INTO bike_sys_permission")).replace("bike_",f.prefix)
                        .replace("CONSTRAINT fk_","CONSTRAINT "+f.prefix+"fk_");
                f.jdbc.execute(ddl);f.tables.add("user_customer_scope");
            }
            var config=new Configuration(new Environment("scope",new SpringManagedTransactionFactory(),f.source));config.setMapUnderscoreToCamelCase(true);
            for(String name:List.of("CustomerScopeMapper","MasterMapper","VehicleMapper","TraceMapper"))try(var input=Resources.getResourceAsStream("mapper/"+name+".xml")) {
                byte[] xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",f.prefix).getBytes(StandardCharsets.UTF_8);
                new XMLMapperBuilder(new ByteArrayInputStream(xml),config,name,config.getSqlFragments()).parse();
            }
            var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            mapper=session.getMapper(CustomerScopeMapper.class);masters=session.getMapper(MasterMapper.class);access=new CustomerScopeAccess(mapper);
            trace=session.getMapper(com.bike.server.trace.TraceMapper.class);
            management=new CustomerScopeService(mapper,audit,new ObjectMapper().findAndRegisterModules());
            when(audit.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
            orders=new OrderServiceImpl(f.orders);
            for(Object service:List.of(f.pis,f.locks,f.conversion,f.piConversion,f.renew,f.stock,f.dispatch,f.logistics,orders))ReflectionTestUtils.setField(service,"customerScope",access);
            grant(1,0,List.of(1L));grant(2,0,List.of(2L));
        }
        CustomerScopeService.View grant(long id,long version,List<Long> ids) {
            login(3,"WEB_OPERATOR","PERM_CUSTOMER_SCOPE_MANAGE");
            try{return f.transaction.execute(status->management.save(id,new CustomerScopeService.Request(version,ids,"核对客户分工")));}
            finally{SecurityContextHolder.clearContext();}
        }
        <T>T as(long user,Supplier<T> work) {
            login(user,"WEB_OPERATOR",SALES);try{return f.transaction.execute(status->work.get());}finally{SecurityContextHolder.clearContext();}
        }
        public void close(){f.close();}
    }
    @Test void listsCountsOptionsAndMasterSearchFilterBeforePagination()throws Exception {
        try(var t=new Fixture()) {
            var own=t.as(1,()->t.f.pis.create(request(1)));
            for(int i=0;i<103;i++)t.as(2,()->t.f.pis.create(request(2)));
            t.as(1,()->{
                var page=t.f.pis.list(null,null,1,1);assertThat(page.total()).isEqualTo(1);assertThat(page.records()).extracting(PiView::getId).containsExactly(own.getId());
                assertThat(t.f.pis.list(null,null,2,1).records()).isEmpty();
                assertThat(t.f.pis.list("客户2",null,1,100).total()).isZero();
                assertThat(t.masters.select("customers",null)).extracting(MasterItem::id).containsExactly(1L);
                assertThat(t.masters.select("customers","客户2")).isEmpty();
                assertThat(t.f.pis.options(null).customers()).hasSize(1);
                var cols=List.of("客户编号","客户名称","联系人","联系电话","状态","创建时间");
                assertThat(t.f.modules.countRows("customers",null,cols,null,true)).isEqualTo(1);
                assertThat(t.f.modules.selectRowsPage("customers",null,cols,"客户2","创建时间",0,100,true)).isEmpty();
                return null;
            });
            t.as(2,()->{assertThat(t.f.pis.list(null,null,1,100).total()).isEqualTo(103);return null;});
            login(3,"WEB_OPERATOR","PERM_PI_READ","PERM_CUSTOMER_SCOPE_ALL");assertThat(t.f.pis.list(null,null,1,100).total()).isEqualTo(104);
        }
    }
    @Test void directIdsWritesOrderNumbersAndLockOptionsCannotCrossCustomer()throws Exception {
        try(var t=new Fixture()) {
            var other=t.as(2,()->t.f.pis.create(request(2)));
            var order=t.as(2,()->t.orders.create(new SalesOrderCreateRequest(2L,null,4L,1,null,null)));
            t.as(1,()->{
                denied(()->t.f.pis.get(other.getId()));denied(()->t.f.pis.create(request(2)));
                denied(()->t.f.pis.update(other.getId(),request(1)));denied(()->t.f.pis.confirm(other.getId()));
                denied(()->t.f.pis.cancel(other.getId(),new PiCancelRequest("越权取消")));
                denied(()->t.orders.get(order.getId()));denied(()->t.orders.getByNo(order.getOrderNo()));
                denied(()->t.orders.confirm(order.getId()));denied(()->t.orders.cancel(order.getId(),"越权"));
                var archive=new PiExportArchive(mock(PiExportMapper.class),(PiMapper)ReflectionTestUtils.getField(t.f.pis,"mapper"),mock(com.bike.server.production.ProductionFileStore.class),new ObjectMapper(),t.audit);
                ReflectionTestUtils.setField(archive,"customerScope",t.access);
                login(1,"WEB_OPERATOR","PERM_PI_READ","PERM_PI_EXPORT");
                denied(()->archive.retry(other.getId(),new PiExportArchive.Retry(0L,"跨客户重排")));
                login(1,"WEB_OPERATOR",SALES);
                denied(()->t.f.piConversion.options(2,null,null,1));
                denied(()->t.f.locks.create(new com.bike.server.lock.SkuLockRequest("wrong",2L,null,null,null,List.of(new com.bike.server.lock.SkuLockRequest.Item(10L,1)))));
                assertThat(t.orders.list(null,null,1,100).total()).isZero();return null;
            });
            assertThat(t.f.scalar("SELECT COUNT(*) FROM @pi WHERE status='DRAFT'")).isEqualTo(1);
            assertThat(t.f.scalar("SELECT COUNT(*) FROM @sales_order WHERE status='DRAFT'")).isEqualTo(1);
        }
    }
    @Test void authorizationIsVersionedValidatedAndAuditFailureRollsBack()throws Exception {
        try(var t=new Fixture()) {
            assertThatThrownBy(()->t.grant(1,0,List.of(2L))).hasMessageContaining("刷新");
            assertThatThrownBy(()->t.grant(1,1,List.of(999L))).hasMessageContaining("不存在");
            when(t.audit.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(0);
            assertThatThrownBy(()->t.grant(1,1,List.of(2L))).hasMessageContaining("审计");
            assertThat(t.mapper.user(1).revision()).isEqualTo(1);assertThat(t.mapper.customers(1)).containsExactly(1L);
            login(1,"WEB_OPERATOR",SALES);assertThatThrownBy(()->t.management.get(2)).hasMessageContaining("无权");
            for(String type:List.of("AGENT","PDA_OPERATOR")) {
                login(1,type,"ROLE_SUPER_ADMIN","PERM_CUSTOMER_SCOPE_MANAGE","PERM_CUSTOMER_SCOPE_ALL");
                assertThatThrownBy(()->t.management.save(1,new CustomerScopeService.Request(1,List.of(2L),"误授权"))).isInstanceOf(BusinessException.class);
            }
        }
    }
    @Test void revokeWaitsForAuthorizedMutationThenAppliesToNextRequest()throws Exception {
        try(var t=new Fixture();var pool=Executors.newFixedThreadPool(2)) {
            var authorized=new CountDownLatch(1);var finish=new CountDownLatch(1);var revoking=new CountDownLatch(1);
            var writer=pool.submit(()->t.as(1,()->{t.access.requireCustomer(1L);authorized.countDown();try{assertThat(finish.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return t.f.pis.create(request(1));}));
            assertThat(authorized.await(10,TimeUnit.SECONDS)).isTrue();
            var revoker=pool.submit(()->{revoking.countDown();return t.grant(1,1,List.of());});
            try {assertThat(revoking.await(10,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->revoker.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
            finally {finish.countDown();}
            var pi=writer.get(10,TimeUnit.SECONDS);assertThat(revoker.get(10,TimeUnit.SECONDS).revision()).isEqualTo(2);
            t.as(1,()->{denied(()->t.f.pis.get(pi.getId()));assertThat(t.f.pis.list(null,null,1,100).total()).isZero();return null;});
        }
    }
    @Test void concurrentEditorsCannotSilentlyOverwriteAssignments()throws Exception {
        try(var t=new Fixture();var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            java.util.function.Function<List<Long>,Callable<String>> task=ids->()->{start.await();try{t.grant(1,1,ids);return "saved";}catch(BusinessException e){return e.getCode();}};
            var a=pool.submit(task.apply(List.of(1L,2L)));var b=pool.submit(task.apply(List.of()));start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("saved","CUSTOMER_SCOPE_CONFLICT");
        }
    }
    @Test void exportedWorkbookAndGlobalAuditPreviewCannotRevealOtherCustomers()throws Exception {
        try(var t=new Fixture()) {
            var module=new com.bike.server.module.ModuleServiceImpl(t.f.modules);
            ReflectionTestUtils.setField(module,"customerScope",t.access);
            var transfer=new com.bike.server.transfer.ExcelTransferService(null,null,null,module,
                    mock(com.bike.server.transfer.TransferAuditMapper.class),t.f.validation.getValidator());
            login(1,"WEB_OPERATOR","PERM_DATA_EXPORT","PERM_MASTER_READ");
            byte[] data=t.f.transaction.execute(status->transfer.export("customers",null));
            try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(data))) {
                var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(1);
                assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("客户1");
            }
            assertThat(t.f.modules.selectRows("logs",null,true)).isEmpty();
            assertThat(t.f.dashboard.selectRecentOperations(true)).isEmpty();
            byte[] hidden=t.f.transaction.execute(status->transfer.export("customers","客户2"));
            try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(hidden))) {
                assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();
            }
        }
    }
    @Test void delegatedManagerHasPagedAssignmentMetadataWithoutBusinessAccess()throws Exception {
        try(var t=new Fixture()) {
            t.f.exec("INSERT INTO @sys_user(id,username,display_name,user_type) VALUES(4,'external','代理','AGENT'),(5,'pda','仓管','PDA_OPERATOR')");
            login(3,"WEB_OPERATOR","PERM_CUSTOMER_SCOPE_MANAGE");
            var users=t.management.users("sales",1,1);assertThat(users.total()).isEqualTo(2);assertThat(users.records()).extracting(CustomerScopeMapper.UserOption::username).containsExactly("sales-a");
            assertThat(t.management.users("sales",2,1).records()).extracting(CustomerScopeMapper.UserOption::username).containsExactly("sales-b");
            assertThat(t.management.users(null,1,100).total()).isEqualTo(3);
            assertThat(t.management.customers(null,2,1).records()).extracting(CustomerScopeMapper.CustomerOption::id).containsExactly(2L);
            assertThat(t.management.customers("客户2",1,20).total()).isEqualTo(1);
            assertThat(t.management.get(1).customers()).extracting(CustomerScopeMapper.CustomerOption::id).containsExactly(1L);
            denied(()->t.access.requireCustomer(1L));
            for(String type:List.of("AGENT","PDA_OPERATOR")) {
                login(3,type,"ROLE_SUPER_ADMIN","PERM_CUSTOMER_SCOPE_MANAGE");
                assertThatThrownBy(()->t.management.users(null,1,20)).isInstanceOf(BusinessException.class);
                assertThatThrownBy(()->t.management.customers(null,1,20)).isInstanceOf(BusinessException.class);
            }
            login(1,"WEB_OPERATOR",SALES);
            assertThatThrownBy(()->t.management.users(null,1,20)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void reusedPartHistoryCannotRevealAnotherCustomersVehicleRecords()throws Exception {
        try(var t=new Fixture()) {
            t.f.exec("CREATE TABLE @rework_record(id BIGINT PRIMARY KEY,rework_no VARCHAR(64),frame_sn VARCHAR(64),reason VARCHAR(500),status VARCHAR(32),operator_id BIGINT,operation_time DATETIME(3))");t.f.tables.add("rework_record");
            t.f.exec("CREATE TABLE @rework_part_detail(rework_id BIGINT,part_type VARCHAR(32),old_part_sn VARCHAR(64),new_part_sn VARCHAR(64))");t.f.tables.add("rework_part_detail");
            t.f.exec("UPDATE @vehicle SET current_customer_id=CASE id WHEN 100 THEN 1 WHEN 101 THEN 2 END WHERE id IN (100,101)");
            t.f.exec("INSERT INTO @vehicle_part_binding(vehicle_id,part_type,part_sn,is_current) VALUES(100,'MOTOR','REUSED',1),(101,'MOTOR','REUSED',0)");
            t.f.exec("INSERT INTO @rework_record SELECT id,CONCAT('RW-',id),frame_sn,'更换部件','COMPLETED',1,NOW(3) FROM @vehicle WHERE id IN (100,101)");
            t.f.exec("INSERT INTO @rework_part_detail VALUES(100,'MOTOR','OLD','REUSED'),(101,'MOTOR','REUSED','NEW')");
            t.as(1,()->{
                assertThat(t.trace.selectVehiclesByPart("REUSED")).extracting(com.bike.server.vehicle.VehicleView::getId).containsExactly(100L);
                assertThat(t.trace.selectStockInRecordsByPart("REUSED")).extracting(com.bike.server.trace.TraceStockInRecord::getStockInNo).containsExactly("IN100");
                assertThat(t.trace.selectReworkRecordsByPart("REUSED")).extracting(com.bike.server.trace.TraceReworkRecord::getReworkNo).containsExactly("RW-100");return null;
            });
            login(3,"WEB_OPERATOR","PERM_CUSTOMER_SCOPE_ALL");
            assertThat(t.trace.selectStockInRecordsByPart("REUSED")).hasSize(2);
            assertThat(t.trace.selectReworkRecordsByPart("REUSED")).hasSize(2);
        }
    }
}
