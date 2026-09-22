package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.bike.server.common.*;
import com.bike.server.production.ProductionFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class PiPublishingMysqlTest {
    @TempDir Path directory;
    static void login(String... authorities){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test","",Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));}
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    static class Fixture implements AutoCloseable {
        final String prefix="pipub_"+UUID.randomUUID().toString().replace("-","").substring(0,8)+"_";
        final UnpooledDataSource source=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",System.getenv("BIKE_MYSQL_TEST_URL"),System.getenv("BIKE_MYSQL_TEST_USER"),System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
        final DataSourceTransactionManager manager=new DataSourceTransactionManager(source);
        final TransactionTemplate tx=new TransactionTemplate(manager);
        final PiTemplateMapper templates;
        final PiExportMapper exports;
        final PiMapper pis=mock(PiMapper.class);
        final OperationLogMapper logs=mock(OperationLogMapper.class);
        final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        Fixture()throws Exception {
            var config=new Configuration(new Environment("pi-publishing",new SpringManagedTransactionFactory(),source));config.setMapUnderscoreToCamelCase(true);
            for(String name:List.of("PiTemplateMapper","PiExportMapper"))try(var input=Resources.getResourceAsStream("mapper/"+name+".xml")) {
                byte[] xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix).getBytes(StandardCharsets.UTF_8);
                new XMLMapperBuilder(new ByteArrayInputStream(xml),config,name,config.getSqlFragments()).parse();
            }
            var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));templates=session.getMapper(PiTemplateMapper.class);exports=session.getMapper(PiExportMapper.class);
            for(String file:List.of("V38__pi_published_templates.sql","V39__pi_export_archive.sql"))try(var input=Resources.getResourceAsStream("db/migration-prod/"+file)) {
                var text=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix);
                String end="ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";sql(text.substring(0,text.indexOf(end)+end.length()));
            }
            when(logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
        }
        void sql(String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
        public void close()throws Exception{sql("DROP TABLE "+prefix+"pi_export");sql("DROP TABLE "+prefix+"pi_template");}
        PiTemplateService templateService(){return new PiTemplateService(templates,logs,json,Validation.buildDefaultValidatorFactory().getValidator());}
        ProductionFileStore store(Path path){return new ProductionFileStore(path.toString(),"local","","","","");}
        PiExportArchive archive(ProductionFileStore store){return new PiExportArchive(exports,pis,store,json,logs);}
        PiView pi(){var p=PiDocumentServiceTest.sample(2);p.setStatus("CONFIRMED");when(pis.selectItems(1)).thenReturn(p.getItems());when(pis.selectById(1)).thenReturn(p);when(pis.lockPi(1)).thenReturn(1L);return p;}
    }
    static PiTemplateRequest template(String version){return new PiTemplateRequest("CONTROLLED",version,"DOMESTIC","DOMESTIC_V1","受控卖方","地址","联系人","银行","000123","收款单位","核对后发布");}
    @Test void templatePublicationIsPermissionControlledImmutableAuditedAndTransactional()throws Exception {
        try(var f=new Fixture()) {
            var service=f.templateService();login("ROLE_WEB_OPERATOR","PERM_PI_WRITE");
            assertThatThrownBy(()->service.publish(template("1"))).isInstanceOf(BusinessException.class);
            login("ROLE_WEB_AGENT","ROLE_SUPER_ADMIN");assertThatThrownBy(()->service.publish(template("1"))).isInstanceOf(BusinessException.class);
            login("ROLE_WEB_OPERATOR","PERM_PI_TEMPLATE_MANAGE");var t=f.tx.execute(s->service.publish(template("1")));
            assertThat(t.bankAccount()).isEqualTo("000123");assertThat(service.list(null,1,20).total()).isEqualTo(1);
            assertThatThrownBy(()->f.tx.execute(s->service.publish(template("1")))).hasMessageContaining("已发布");
            when(f.logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(0);
            assertThatThrownBy(()->f.tx.execute(s->service.publish(template("2")))).hasMessageContaining("审计");assertThat(f.templates.find("CONTROLLED","2")).isNull();
        }
    }
    @Test void publishedSnapshotRejectsUnknownTemplateWrongTradeAndBankTampering()throws Exception {
        try(var f=new Fixture()) {
            login("ROLE_SUPER_ADMIN");var service=f.templateService();service.publish(template("1"));
            var d=new PiDocumentSnapshot("受控卖方","地址","联系人","买方地址","买方联系人","银行","000123","收款单位","款项","交付","CONTROLLED","1");
            service.requirePublished(d,"DOMESTIC");assertThatThrownBy(()->service.requirePublished(d,"EXPORT")).hasMessageContaining("内外销");
            var bad=new PiDocumentSnapshot("受控卖方","地址","联系人","买方地址","买方联系人","银行","ALTERED","收款单位","款项","交付","CONTROLLED","1");
            assertThatThrownBy(()->service.requirePublished(bad,"DOMESTIC")).hasMessageContaining("银行资料");
            assertThatThrownBy(()->service.requirePublished(null,"DOMESTIC")).hasMessageContaining("已发布");
        }
    }
    @Test void bothFormatsAreArchivedFromPublicSnapshotAndDownloadsAreByteIdenticalAfterChanges()throws Exception {
        try(var f=new Fixture();var store=f.store(directory);var worker=new PiExportWorker(f.exports,new PiDocumentService(),store,f.json,f.manager)) {
            var archive=f.archive(store);var p=f.pi();p.setPaymentStatus("SECRET_PAYMENT");p.setReleaseStatus("SECRET_RELEASE");
            f.tx.executeWithoutResult(s->archive.enqueue(p,"CONFIRMATION"));
            String snapshot=f.exports.get(1).snapshotJson();assertThat(snapshot).doesNotContain("SECRET_PAYMENT","SECRET_RELEASE","1000.00");
            assertThat(worker.processNext()).isTrue();assertThat(f.exports.get(1).status()).isEqualTo("READY");
            byte[] pdf=archive.download(1,"pdf"),xlsx=archive.download(1,"xlsx");
            p.setCustomerName("CHANGED");p.setStatus("RELEASED");assertThat(worker.processNext()).isFalse();
            assertThat(archive.download(1,"pdf")).isEqualTo(pdf);assertThat(archive.download(1,"xlsx")).isEqualTo(xlsx);
            String response=f.json.writeValueAsString(f.exports.get(1));assertThat(response).doesNotContain("snapshotJson","claimToken","pdfKey","xlsxKey","leaseUntil");
            try(var document=org.apache.pdfbox.Loader.loadPDF(pdf)){assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document)).doesNotContain("门槛","SECRET_");}
            Files.write(directory.resolve(f.exports.get(1).pdfKey().substring(6)),new byte[]{1,2,3});
            assertThatThrownBy(()->archive.download(1,"pdf")).hasMessageContaining("完整性");
        }
    }
    @Test void secondFormatFailureRetainsFirstFileAndRetryDoesNotModifyPiOrInventory()throws Exception {
        try(var f=new Fixture();var store=f.store(directory)) {
            var renderer=spy(new PiDocumentService());doThrow(new IllegalStateException("test failure")).when(renderer).excel(any());
            var archive=f.archive(store);archive.enqueue(f.pi(),"CONFIRMATION");
            try(var worker=new PiExportWorker(f.exports,renderer,store,f.json,f.manager)){worker.processNext();}
            var failed=f.exports.get(1);assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.pdfKey()).isNotNull();assertThat(failed.xlsxKey()).isNull();
            assertThatThrownBy(()->archive.download(1,"pdf")).hasMessageContaining("尚未就绪");
            login("ROLE_WEB_AGENT","ROLE_SUPER_ADMIN");assertThatThrownBy(()->archive.retry(1,new PiExportArchive.Retry(failed.revision(),"retry"))).isInstanceOf(BusinessException.class);
            login("ROLE_WEB_OPERATOR","PERM_PI_EXPORT");
            assertThatThrownBy(()->archive.retry(1,new PiExportArchive.Retry(1L,"stale"))).hasMessageContaining("已变化");
            f.tx.executeWithoutResult(s->archive.retry(1,new PiExportArchive.Retry(failed.revision(),"retry")));
            try(var worker=new PiExportWorker(f.exports,new PiDocumentService(),store,f.json,f.manager)){worker.processNext();}
            assertThat(f.exports.get(1).status()).isEqualTo("READY");assertThat(f.exports.get(1).pdfKey()).isEqualTo(failed.pdfKey());
            assertThat(f.exports.get(1).pdfSha256()).isEqualTo(failed.pdfSha256());verify(f.pis,never()).updateStatus(anyLong(),any(),any(),any(),anyBoolean());
        }
    }
    @Test void queueRollsBackWithConfirmationAndRetryRollsBackWithAudit()throws Exception {
        try(var f=new Fixture();var store=f.store(directory)) {
            var archive=f.archive(store);var p=f.pi();
            assertThatThrownBy(()->f.tx.executeWithoutResult(s->{archive.enqueue(p,"CONFIRMATION");throw new IllegalStateException("rollback");}));
            assertThat(f.exports.get(1)).isNull();archive.enqueue(p,"CONFIRMATION");f.sql("UPDATE "+f.prefix+"pi_export SET status='FAILED'");
            login("ROLE_SUPER_ADMIN");when(f.logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(0);
            assertThatThrownBy(()->f.tx.executeWithoutResult(s->archive.retry(1,new PiExportArchive.Retry(1L,"retry")))).hasMessageContaining("审计");
            assertThat(f.exports.get(1).status()).isEqualTo("FAILED");assertThat(f.exports.get(1).revision()).isEqualTo(1);
        }
    }
    @Test void concurrentWorkersClaimOnceAndExpiredLeaseCannotReplaceSavedFiles()throws Exception {
        try(var f=new Fixture();var store=f.store(directory);var pool=Executors.newFixedThreadPool(2);
            var a=new PiExportWorker(f.exports,new PiDocumentService(),store,f.json,f.manager);
            var b=new PiExportWorker(f.exports,new PiDocumentService(),store,f.json,f.manager)) {
            f.archive(store).enqueue(f.pi(),"CONFIRMATION");var start=new CountDownLatch(1);
            var one=pool.submit(()->{start.await();return a.processNext();});var two=pool.submit(()->{start.await();return b.processNext();});start.countDown();
            assertThat(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(f.exports.get(1).attempts()).isEqualTo(1);
            f.sql("UPDATE "+f.prefix+"pi_export SET status='PROCESSING',claim_token='stale',lease_until=DATE_SUB(NOW(),INTERVAL 1 MINUTE)");
            var before=f.exports.get(1);assertThat(a.processNext()).isTrue();assertThat(f.exports.get(1).status()).isEqualTo("READY");
            assertThat(f.exports.saveFile(1,"stale","pdf","bad","bad",1)).isZero();assertThat(f.exports.get(1).pdfKey()).isEqualTo(before.pdfKey());
        }
    }
}
