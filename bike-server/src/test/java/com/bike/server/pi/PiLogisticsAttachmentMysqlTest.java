package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.bike.server.aftersales.*;
import com.bike.server.common.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class PiLogisticsAttachmentMysqlTest {
    @TempDir Path directory;
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    static class Fixture extends PiPublishingMysqlTest.Fixture {
        final PiLogisticsAttachmentMapper attachments;
        final PiService viewService=mock(PiService.class);
        Fixture()throws Exception {
            sql("CREATE TABLE "+prefix+"pi_logistics(id BIGINT PRIMARY KEY,status VARCHAR(32)) ENGINE=InnoDB");
            sql("INSERT INTO "+prefix+"pi_logistics VALUES(1,'PLANNED'),(2,'SHIPPED')");
            try(var input=Resources.getResourceAsStream("db/migration-prod/V44__pi_logistics_attachments.sql")) {
                sql(new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix).replace("fk_pi_logistics_attachment_parent",prefix+"parent"));
            }
            var config=new Configuration(new Environment("logistics-files",new SpringManagedTransactionFactory(),source));config.setMapUnderscoreToCamelCase(true);
            try(var input=Resources.getResourceAsStream("mapper/PiLogisticsAttachmentMapper.xml")) {
                var xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix).getBytes(StandardCharsets.UTF_8);
                new XMLMapperBuilder(new ByteArrayInputStream(xml),config,"PiLogisticsAttachmentMapper",config.getSqlFragments()).parse();
            }
            attachments=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(PiLogisticsAttachmentMapper.class);
            var p=pi();var logistics=new PiLogisticsView();logistics.setId(1L);p.setLogistics(List.of(logistics));when(viewService.get(1)).thenReturn(p);
            var another=pi();var otherLogistics=new PiLogisticsView();otherLogistics.setId(2L);another.setLogistics(List.of(otherLogistics));when(viewService.get(2)).thenReturn(another);
        }
        PiLogisticsAttachmentService service(com.bike.server.production.ProductionFileStore store) {
            var target=new PiLogisticsAttachmentService(viewService,attachments,store,new com.bike.server.common.BusinessUploadValidator(),logs);
            var proxy=new org.springframework.aop.framework.ProxyFactory(target);proxy.setProxyTargetClass(true);
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            return (PiLogisticsAttachmentService)proxy.getProxy();
        }
        @Override public void close()throws Exception {sql("DROP TABLE "+prefix+"pi_logistics_attachment");sql("DROP TABLE "+prefix+"pi_logistics");super.close();}
    }
    @Test void ownershipVisibilityIntegrityAndAuditRollbackApplyToRealObjects()throws Exception {
        try(var f=new Fixture();var store=f.store(directory)) {
            var service=f.service(store);var bytes=PiImageServiceTest.picture();var file=new MockMultipartFile("file","logistics.png","image/png",bytes);
            PiPublishingMysqlTest.login("ROLE_WEB_OPERATOR","PERM_PI_READ","PERM_PI_LOGISTICS_WRITE");
            var a=service.upload(1,1,"public","CUSTOMER",file);var b=service.upload(1,1,"internal","INTERNAL",file);
            assertThat(service.download(1,1,a.id()).bytes()).isEqualTo(bytes);assertThat(service.list(1,1)).hasSize(2);
            assertThatThrownBy(()->service.list(1,2)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->service.download(2,2,a.id())).isInstanceOf(BusinessException.class);
            assertThat(f.json.writeValueAsString(a)).doesNotContain("objectKey","requestId");
            PiPublishingMysqlTest.login("ROLE_WEB_AGENT","ROLE_SUPER_ADMIN");assertThat(service.list(1,1)).extracting(PiLogisticsAttachment::id).containsExactly(a.id());
            assertThatThrownBy(()->service.download(1,1,b.id())).isInstanceOf(BusinessException.class);assertThatThrownBy(()->service.upload(1,1,"agent","CUSTOMER",file)).isInstanceOf(BusinessException.class);
            PiPublishingMysqlTest.login("ROLE_WEB_OPERATOR","PERM_PI_WRITE");assertThatThrownBy(()->service.upload(1,1,"sales","CUSTOMER",file)).isInstanceOf(BusinessException.class);
            PiPublishingMysqlTest.login("ROLE_WEB_OPERATOR","PERM_PI_READ","PERM_PI_LOGISTICS_WRITE");
            when(f.logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(0);
            assertThatThrownBy(()->service.upload(1,1,"rollback","CUSTOMER",file)).hasMessageContaining("审计");assertThat(f.attachments.count(1)).isEqualTo(2);
            try(var all=Files.list(directory)){assertThat(all.count()).isEqualTo(2);}
            assertThatThrownBy(()->service.upload(1,1,"html","CUSTOMER",new MockMultipartFile("file","bad.pdf","application/pdf","<html>bad</html>".getBytes()))).isInstanceOf(BusinessException.class);
            Files.write(directory.resolve(a.objectKey().substring(6)),new byte[]{1});assertThatThrownBy(()->service.download(1,1,a.id())).hasMessageContaining("完整性");
        }
    }
    @Test void concurrentRetriesCreateOneFileAndWithdrawnLogisticsPreservesReadOnlyHistory()throws Exception {
        try(var f=new Fixture();var store=f.store(directory);var pool=Executors.newFixedThreadPool(2)) {
            var service=f.service(store);var file=new MockMultipartFile("file","logistics.png","image/png",PiImageServiceTest.picture());var start=new CountDownLatch(1);
            Callable<PiLogisticsAttachment> task=()->{start.await();PiPublishingMysqlTest.login("ROLE_WEB_OPERATOR","PERM_PI_LOGISTICS_WRITE");try{return service.upload(1,1,"same","CUSTOMER",file);}finally{SecurityContextHolder.clearContext();}};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();var one=a.get(15,TimeUnit.SECONDS);assertThat(b.get(15,TimeUnit.SECONDS).id()).isEqualTo(one.id());
            assertThat(f.attachments.count(1)).isEqualTo(1);try(var all=Files.list(directory)){assertThat(all.count()).isEqualTo(1);}
            PiPublishingMysqlTest.login("ROLE_WEB_OPERATOR","PERM_PI_LOGISTICS_WRITE");f.sql("UPDATE "+f.prefix+"pi_logistics SET status='WITHDRAWN' WHERE id=1");
            assertThat(service.upload(1,1,"same","CUSTOMER",file).id()).isEqualTo(one.id());assertThat(service.download(1,1,one.id()).bytes()).isEqualTo(file.getBytes());
            assertThatThrownBy(()->service.upload(1,1,"new","CUSTOMER",file)).hasMessageContaining("已撤回");
            assertThatThrownBy(()->service.upload(1,1,"same","INTERNAL",file)).hasMessageContaining("其他物流附件");
        }
    }
}
