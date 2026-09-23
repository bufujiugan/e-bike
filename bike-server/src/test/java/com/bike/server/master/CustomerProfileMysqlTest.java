package com.bike.server.master;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.bike.server.common.*;
import com.bike.server.pi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class CustomerProfileMysqlTest {
    static void login(String... authorities){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test","",Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));}
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    static CustomerProfileRequest request(long revision,String address){return new CustomerProfileRequest(revision,address,"DE","purchasing@example.invalid","eur","出口业务组","核对客户资料");}
    static class Fixture implements AutoCloseable {
        final String prefix="custprof_"+UUID.randomUUID().toString().replace("-","").substring(0,8)+"_";
        final UnpooledDataSource source=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",System.getenv("BIKE_MYSQL_TEST_URL"),System.getenv("BIKE_MYSQL_TEST_USER"),System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
        final CustomerProfileMapper mapper;
        final OperationLogMapper logs=mock(OperationLogMapper.class);
        final CustomerProfileService service;
        final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        Fixture()throws Exception {
            sql("CREATE TABLE "+prefix+"customer(id BIGINT PRIMARY KEY,customer_code VARCHAR(64),customer_name VARCHAR(150),contact_name VARCHAR(100),contact_phone VARCHAR(64),status VARCHAR(32),updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)) ENGINE=InnoDB");
            try(var input=Resources.getResourceAsStream("db/migration-prod/V42__customer_business_profile.sql")) {
                String text=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix).replace("fk_customer_profile_customer",prefix+"fk");
                String end="ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";sql(text.substring(0,text.indexOf(end)+end.length()));
            }
            sql("INSERT INTO "+prefix+"customer(id,customer_code,customer_name,contact_name,contact_phone,status) VALUES(1,'C1','采购公司','采购联系人','00123','ENABLED')");
            var config=new Configuration(new Environment("customer-profile",new SpringManagedTransactionFactory(),source));config.setMapUnderscoreToCamelCase(true);
            try(var input=Resources.getResourceAsStream("mapper/CustomerProfileMapper.xml")) {
                byte[] xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix).getBytes(StandardCharsets.UTF_8);
                new XMLMapperBuilder(new ByteArrayInputStream(xml),config,"CustomerProfileMapper",config.getSqlFragments()).parse();
            }
            mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(CustomerProfileMapper.class);
            var target=new CustomerProfileService(mapper,logs,json,Validation.buildDefaultValidatorFactory().getValidator());
            var proxy=new org.springframework.aop.framework.ProxyFactory(target);proxy.setProxyTargetClass(true);
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new DataSourceTransactionManager(source),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            service=(CustomerProfileService)proxy.getProxy();
            when(logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
        }
        void sql(String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
        public void close()throws Exception{sql("DROP TABLE "+prefix+"customer_profile");sql("DROP TABLE "+prefix+"customer");}
    }
    @Test void profileIsRestrictedValidatedVersionedAndRollsBackWithAudit()throws Exception {
        try(var f=new Fixture()) {
            login("ROLE_WEB_OPERATOR","PERM_PI_WRITE");assertThat(f.service.get(1).revision()).isZero();assertThat(f.service.get(1).email()).isNull();
            assertThatThrownBy(()->f.service.update(1,request(0,"地址"))).isInstanceOf(BusinessException.class);
            for(String role:List.of("ROLE_PDA_OPERATOR","ROLE_WEB_AGENT")) {login(role,"ROLE_SUPER_ADMIN");assertThatThrownBy(()->f.service.get(1)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->f.service.update(1,request(0,"地址"))).isInstanceOf(BusinessException.class);}
            login("ROLE_WEB_OPERATOR","PERM_INVENTORY_READ");assertThatThrownBy(()->f.service.get(1)).isInstanceOf(BusinessException.class);
            login("ROLE_WEB_OPERATOR","PERM_CUSTOMER_PROFILE_WRITE");var saved=f.service.update(1,request(0,"原地址"));assertThat(saved.revision()).isEqualTo(1);assertThat(saved.preferredCurrency()).isEqualTo("EUR");
            assertThatThrownBy(()->f.service.update(1,request(0,"过期页面"))).hasMessageContaining("已更新");
            assertThatThrownBy(()->f.service.update(1,new CustomerProfileRequest(1L,"地址","DE","bad-email","EUR","owner","reason"))).hasMessageContaining("格式");
            assertThatThrownBy(()->f.service.update(1,new CustomerProfileRequest(1L,"地址","DE","ok@example.invalid","ZZZ","owner","reason"))).hasMessageContaining("格式");
            when(f.logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(0);
            assertThatThrownBy(()->f.service.update(1,request(1,"不应提交"))).hasMessageContaining("审计");
            assertThat(f.service.get(1).address()).isEqualTo("原地址");assertThat(f.service.get(1).revision()).isEqualTo(1);
        }
    }
    @Test void concurrentFirstSaveAllowsOneVersionAndRejectsTheOther()throws Exception {
        try(var f=new Fixture();var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<String> task=()->{start.await();login("ROLE_WEB_OPERATOR","PERM_CUSTOMER_PROFILE_WRITE");try {f.service.update(1,request(0,"并发地址"));return "saved";}catch(BusinessException e){return e.getMessage();}finally{SecurityContextHolder.clearContext();}};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("saved","客户商务资料已更新，请刷新后重新核对");
            login("ROLE_WEB_OPERATOR","PERM_CUSTOMER_PROFILE_WRITE");assertThat(f.service.get(1).revision()).isEqualTo(1);
        }
    }
    @Test void piSnapshotDefaultsPreserveOverridesAndStayIndependentOfLaterMasterChanges()throws Exception {
        try(var f=new Fixture()) {
            login("ROLE_SUPER_ADMIN");f.service.update(1,request(0,"原地址"));
            var snapshots=new PiCustomerSnapshotService(f.service);var original=snapshots.capture(1,null);
            assertThat(original.buyerAddress()).isEqualTo("原地址");assertThat(original.shippingAddress()).isEqualTo("原地址");
            assertThat(original.buyerContactName()).isEqualTo("采购联系人");assertThat(original.buyerPhone()).isEqualTo("00123");
            assertThat(original.buyerEmail()).isEqualTo("purchasing@example.invalid");assertThat(original.businessOwner()).isEqualTo("出口业务组");
            String json=f.json.writeValueAsString(original);f.service.update(1,request(1,"新地址"));
            var stored=f.json.readValue(json,PiDocumentSnapshot.class);assertThat(stored.buyerAddress()).isEqualTo("原地址");
            var edited=new PiDocumentSnapshot(null,null,null,"另行约定地址","另行约定联系人",null,null,null,null,null,null,null);
            assertThat(snapshots.capture(1,edited).buyerAddress()).isEqualTo("另行约定地址");assertThat(snapshots.capture(1,edited).buyerCountry()).isEqualTo("DE");
            assertThat(f.json.readValue("{\"buyerAddress\":\"旧单据\"}",PiDocumentSnapshot.class).buyerEmail()).isNull();
        }
    }
}
