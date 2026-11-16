package com.bike.server.europe;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.production.migration.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BIKE_EUROPE_MYSQL_TEST",matches="true")
class EuropeSalesMysqlTest {
    private SqlSessionFactory sessions(UnpooledDataSource source) throws Exception {
        var config=new Configuration(new Environment("europe-test",new JdbcTransactionFactory(),source));
        config.setMapUnderscoreToCamelCase(true);
        for(String resource:List.of("mapper/EuropeSalesFixtureMapper.xml","mapper/EuropeSalesMapper.xml"))
            try(var input=Resources.getResourceAsStream(resource)) { new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse(); }
        return new SqlSessionFactoryBuilder().build(config);
    }
    @Test void migratesV58AndExecutesFactoryAndSettlementMappers() throws Exception {
        String base="jdbc:mysql://127.0.0.1:3337/";
        String options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        var bootstrap=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",base+options,"root","");
        try(var session=sessions(bootstrap).openSession(true)) { session.update("europeSalesFixture.createDatabase"); }
        var source=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",base+"bike_europe_migration_test_20261116"+options,"root","");
        var flyway=Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .javaMigrations(new V16__production_workflow(),new V57__unified_fulfillment(),new V58__europe_sales())
                .table("bike_schema_history").load();
        flyway.migrate();flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("58");
        var auth=new UsernamePasswordAuthenticationToken("europe-test",null,List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        auth.setDetails(new UserAuthRecord(980001L,"europe-test","unused","INTERNAL",null,null,true));
        SecurityContextHolder.getContext().setAuthentication(auth);
        // 测试事务回滚夹具及流水，迁移结构保留在独立测试库中。
        try(var session=sessions(source).openSession(false)) {
            for(String fixture:List.of("customer","model","order","item")) session.insert("europeSalesFixture."+fixture);
            var mapper=session.getMapper(EuropeSalesMapper.class);
            var json=new ObjectMapper().findAndRegisterModules();
            var request=json.readValue("{\"requestId\":\"MYSQL-QA\",\"revision\":1,\"customerId\":980001,\"businessType\":\"FACTORY_EXPORT\",\"currencyCode\":\"EUR\",\"netAmount\":\"9999999999999999.99\",\"orderIds\":[980001]}",FactorySalesRequest.class);
            assertThat(mapper.insertOrder("FS-MYSQL-QA",request,json.writeValueAsString(request),null)).isEqualTo(1);
            var saved=mapper.selectByRequestId("MYSQL-QA");long id=saved.id();
            assertThat(saved.customerName()).isEqualTo("欧洲验收客户");
            assertThat(mapper.selectOrderForUpdate(id).revision()).isEqualTo(1);
            assertThat(mapper.countOrders("FS-MYSQL",null,"FACTORY_EXPORT")).isEqualTo(1);
            assertThat(mapper.selectOrders("FS-MYSQL",null,"FACTORY_EXPORT",0,20)).hasSize(1);
            assertThat(mapper.updateSteps(id,1,"{\"COLLECTION\":{\"operatorName\":\"经办人\"}}")).isEqualTo(1);
            assertThat(mapper.updateSteps(id,1,"{}")).isZero();
            var updatedNode=json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(request);
            updatedNode.put("revision",2);
            var updated=json.treeToValue(updatedNode,FactorySalesRequest.class);
            assertThat(mapper.updateOrder(id,updated,json.writeValueAsString(updated))).isEqualTo(1);
            assertThat(mapper.insertOrderLink(id,980001)).isEqualTo(1);
            assertThat(mapper.selectFactoryOrderForSalesOrder(980001)).isEqualTo(id);
            assertThat(mapper.selectLinkedOrders(id)).singleElement().satisfies(order->{assertThat(order.orderQty()).isEqualTo(2);assertThat(order.shippedQty()).isZero();});
            var payment=new SalesSettlementRequest("MONEY-QA",new BigDecimal("9999999999999999.99"),"EUR",LocalDateTime.of(2026,1,1,12,0),"Totem",null,null);
            assertThat(mapper.insertSettlement("FACTORY",id,payment,null)).isEqualTo(1);
            var entry=mapper.selectSettlementRequest("FACTORY",id,"MONEY-QA");
            assertThat(entry.amount()).isEqualByComparingTo(payment.amount());
            assertThat(mapper.updateSettlementSupplement("FACTORY",id,entry.id(),new SalesSettlementSupplement("Totem","REF-QA","后补"))).isEqualTo(1);
            assertThat(mapper.selectSettlements("FACTORY",id)).singleElement().satisfies(row->assertThat(row.referenceNo()).isEqualTo("REF-QA"));
            assertThat(mapper.reverseSettlement("FACTORY",id,entry.id(),"验收更正",null)).isEqualTo(1);
            assertThat(mapper.reverseSettlement("FACTORY",id,entry.id(),"重复",null)).isZero();
            assertThat(mapper.deleteOrderLink(id,980001)).isEqualTo(1);
            session.rollback();
        } finally { SecurityContextHolder.clearContext(); }
    }
}
