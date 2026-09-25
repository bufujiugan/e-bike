package com.bike.server.files;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.bike.server.common.*;
import com.bike.server.pi.*;
import com.bike.server.production.ProductionFileStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class FileUploadMysqlTest {
    @TempDir Path directory;
    static void login(String... roles){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("file-test","",Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));}
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static byte[] picture()throws Exception{var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    static class Fixture implements AutoCloseable {
        final String prefix="file_"+UUID.randomUUID().toString().replace("-","").substring(0,8)+"_";
        final UnpooledDataSource source=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",System.getenv("BIKE_MYSQL_TEST_URL"),System.getenv("BIKE_MYSQL_TEST_USER"),System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
        final DataSourceTransactionManager manager=new DataSourceTransactionManager(source);
        final TransactionTemplate tx=new TransactionTemplate(manager);
        final JdbcTemplate sql=new JdbcTemplate(source);
        final FileUploadMapper mapper;
        final PiConfirmationMapper confirmations;
        final PiExportMapper exports;
        final OperationLogMapper logs;
        final FileUploadJournal journal;
        final List<String> tables=new ArrayList<>();
        Fixture()throws Exception {
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            var config=new Configuration(new Environment("files",new SpringManagedTransactionFactory(),source));config.setMapUnderscoreToCamelCase(true);
            for(String name:List.of("FileUploadMapper","PiConfirmationMapper","PiExportMapper","OperationLogMapper"))try(var input=Resources.getResourceAsStream("mapper/"+name+".xml")) {
                byte[] xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix).getBytes(StandardCharsets.UTF_8);
                new XMLMapperBuilder(new ByteArrayInputStream(xml),config,name,config.getSqlFragments()).parse();
            }
            var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));mapper=session.getMapper(FileUploadMapper.class);
            confirmations=session.getMapper(PiConfirmationMapper.class);exports=session.getMapper(PiExportMapper.class);logs=spy(session.getMapper(OperationLogMapper.class));
            for(String file:List.of("V49__file_upload_review.sql","V41__pi_confirmation_attachments.sql","V39__pi_export_archive.sql")) {
                String text=new String(Resources.getResourceAsStream("db/migration/"+file).readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix).replace("fk_file_review_upload",prefix+"fk_review").replace("CONSTRAINT ck_","CONSTRAINT "+prefix+"ck_");
                for(String statement:text.split(";\\s*(?:\\r?\\n|$)"))if(statement.stripLeading().startsWith("CREATE TABLE")){sql.execute(statement);tables.add(statement.strip().split("\\s+")[2].substring(prefix.length()));}
            }
            for(String table:List.of("production_attachment","pi_payment_attachment","pi_logistics_attachment","after_sales_attachment"))create(table,"object_key VARCHAR(512)");
            create("pi_payment","voucher_object_key VARCHAR(512)");
            create("operation_log","id BIGINT AUTO_INCREMENT PRIMARY KEY,business_type VARCHAR(64),business_id VARCHAR(64),action VARCHAR(64),operator_id BIGINT,operator_name VARCHAR(100),before_data TEXT,after_data TEXT,client_ip VARCHAR(64),remark TEXT");
            // Use a separate DataSource identity, so JdbcTemplate cannot join the surrounding business transaction.
            var intentSource=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",System.getenv("BIKE_MYSQL_TEST_URL"),System.getenv("BIKE_MYSQL_TEST_USER"),System.getenv("BIKE_MYSQL_TEST_PASSWORD"));
            journal=new FileUploadJournal(mapper,new FileUploadIntentStore(intentSource,prefix+"file_upload"));
        }
        void create(String table,String fields){sql.execute("CREATE TABLE "+prefix+table+" ("+fields+") ENGINE=InnoDB");tables.add(table);}
        ProductionFileStore store(Path root){var store=new ProductionFileStore(root.toString(),"local","","","","");ReflectionTestUtils.setField(store,"uploadJournal",journal);return store;}
        FileReviewService review(ProductionFileStore files){return new FileReviewService(mapper,files,logs,manager);}
        PiConfirmationService confirmations(ProductionFileStore files) {
            var pis=mock(PiService.class);var pi=new PiView();pi.setId(1L);pi.setStatus("DRAFT");when(pis.get(1)).thenReturn(pi);
            var piMapper=mock(PiMapper.class);when(piMapper.lockPi(1)).thenReturn(1L);
            return new PiConfirmationService(pis,piMapper,confirmations,files,new BusinessUploadValidator(),logs);
        }
        String intent(String suffix,byte[] bytes)throws Exception {
            String key="local:"+UUID.randomUUID()+"."+suffix;
            assertThat(mapper.insert(key,new FileUploadJournal.Context("PI_CONFIRMATION","1","file."+suffix,hash(bytes)),bytes.length,"image/png",null,"file-test")).isEqualTo(1);return key;
        }
        public void close(){Collections.reverse(tables);for(String table:tables)sql.execute("DROP TABLE "+prefix+table);}
    }
    @Test void actualAttachmentCommitAndAuditRollbackPreserveIndependentIntent()throws Exception {
        try(var f=new Fixture();var files=f.store(directory)) {
            login("ROLE_SUPER_ADMIN");var service=f.confirmations(files);byte[] bytes=picture();var upload=new MockMultipartFile("file","proof.png","image/png",bytes);
            var attachment=f.tx.execute(s->{try{return service.upload(1,"good","CUSTOMER",upload);}catch(IOException e){throw new UncheckedIOException(e);}});
            assertThat(f.mapper.get(attachment.objectKey()).state()).isEqualTo("BOUND");assertThat(files.exists(attachment.objectKey())).isTrue();
            doReturn(0).when(f.logs).insert(any(),any(),any(),any(),any(),any(),any(),any(),any());
            assertThatThrownBy(()->f.tx.execute(s->{try{return service.upload(1,"bad","CUSTOMER",upload);}catch(IOException e){throw new UncheckedIOException(e);}})).hasMessageContaining("审计");
            var rows=f.mapper.list("REVIEW",0,10);assertThat(rows).hasSize(1);assertThat(files.exists(rows.getFirst().objectKey())).isFalse();
            assertThat(f.confirmations.count(1)).isEqualTo(1);
            reset(f.logs);var review=f.review(files);var updated=review.reconcile(new FileReviewService.Request(rows.getFirst().objectKey(),rows.getFirst().version(),"失败文件检查"));
            assertThat(updated.objectState()).isEqualTo("MISSING");assertThat(updated.state()).isEqualTo("REVIEW");assertThat(review.history(updated.objectKey())).hasSize(1);
        }
    }
    @Test void unboundCommitIsRejectedAndCleanupRequiresDurableAuditedApproval()throws Exception {
        try(var f=new Fixture();var files=f.store(directory)) {
            login("PERM_FILE_REVIEW_MANAGE");String key=files.key(UUID.randomUUID()+".png");byte[] bytes=picture();
            var context=new FileUploadJournal.Context("SKU_IMAGE","1","image.png",hash(bytes));
            assertThatThrownBy(()->f.tx.executeWithoutResult(s->{try{files.write(key,new ByteArrayInputStream(bytes),bytes.length,"image/png",context);}catch(IOException e){throw new UncheckedIOException(e);}})).hasMessageContaining("业务绑定");
            var row=f.mapper.get(key);assertThat(row.state()).isEqualTo("REVIEW");assertThat(files.exists(key)).isTrue();
            var review=f.review(files);doReturn(0).when(f.logs).insert(any(),any(),any(),any(),any(),any(),any(),any(),any());
            assertThatThrownBy(()->review.cleanup(new FileReviewService.Request(key,row.version(),"确认未绑定"))).hasMessageContaining("审计");
            assertThat(files.exists(key)).isTrue();assertThat(f.mapper.get(key).version()).isEqualTo(row.version());assertThat(review.history(key)).isEmpty();
            reset(f.logs);var cleaned=review.cleanup(new FileReviewService.Request(key,row.version(),"确认上传失败，清理文件"));
            assertThat(cleaned.state()).isEqualTo("CLEANED");assertThat(files.exists(key)).isFalse();assertThat(review.history(key)).hasSize(1);
            assertThatThrownBy(()->review.cleanup(new FileReviewService.Request(key,row.version(),"重复提交"))).hasMessageContaining("已变化");
        }
    }
    @Test void crashedIntentsAreRecoveredWithoutAutomaticDeletionAndAllPointersProtectFiles()throws Exception {
        try(var f=new Fixture();var files=f.store(directory)) {
            byte[] bytes=picture();String orphan=f.intent("png",bytes),missing=f.intent("png",bytes);
            files.write(orphan,new ByteArrayInputStream(bytes),bytes.length,"image/png");
            f.sql.update("UPDATE "+f.prefix+"file_upload SET created_at=DATE_SUB(NOW(),INTERVAL 20 MINUTE)");
            f.review(files).tick();assertThat(f.mapper.get(orphan).state()).isEqualTo("REVIEW");assertThat(f.mapper.get(orphan).objectState()).isEqualTo("PRESENT");
            assertThat(f.mapper.get(missing).objectState()).isEqualTo("MISSING");assertThat(files.exists(orphan)).isTrue();
            login("PERM_FILE_REVIEW_MANAGE");
            for(String table:List.of("production_attachment","pi_payment_attachment","pi_logistics_attachment","after_sales_attachment","pi_payment","pi_export","pi_confirmation_attachment")) {
                String key=f.intent("png",bytes);files.write(key,new ByteArrayInputStream(bytes),bytes.length,"image/png");
                if(table.equals("pi_export"))f.sql.update("INSERT INTO "+f.prefix+table+"(pi_id,source_type,snapshot_json,pdf_key) VALUES(1,'TEST','{}',?)",key);
                else if(table.equals("pi_confirmation_attachment"))f.confirmations.insert(new PiConfirmationAttachment(UUID.randomUUID().toString(),1,"pointer",key,"proof.png","image/png",bytes.length,hash(bytes),"CUSTOMER",null,null));
                else f.sql.update("INSERT INTO "+f.prefix+table+"("+(table.equals("pi_payment")?"voucher_object_key":"object_key")+") VALUES(?)",key);
                f.mapper.failed(key);
                assertThatThrownBy(()->f.review(files).cleanup(new FileReviewService.Request(key,1L,"不能删除引用文件"))).hasMessageContaining("业务引用");
                var bound=f.review(files).reconcile(new FileReviewService.Request(key,1L,"恢复绑定状态"));assertThat(bound.state()).as(table).isEqualTo("BOUND");assertThat(files.exists(key)).isTrue();
            }
        }
    }
    @Test void inFlightBindingSkipsReconciliationAndNeverDeletesCommittedFile()throws Exception {
        try(var f=new Fixture();var files=f.store(directory);var pool=Executors.newSingleThreadExecutor()) {
            byte[] bytes=picture();String key=files.key(UUID.randomUUID()+".png");var ready=new CountDownLatch(1);var finish=new CountDownLatch(1);
            var task=pool.submit(()->f.tx.executeWithoutResult(s->{try {
                files.write(key,new ByteArrayInputStream(bytes),bytes.length,"image/png",new FileUploadJournal.Context("SKU_IMAGE","1","safe.png",hash(bytes)));
                ready.countDown();if(!finish.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout");
                f.sql.update("INSERT INTO "+f.prefix+"production_attachment(object_key) VALUES(?)",key);
            }catch(Exception e){throw new IllegalStateException(e);}}));
            try {assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();login("PERM_FILE_REVIEW_MANAGE");
                assertThatThrownBy(()->f.review(files).reconcile(new FileReviewService.Request(key,0L,"并发检查"))).hasMessageContaining("正在上传");
                f.review(files).process(key);assertThat(files.exists(key)).isTrue();
            }finally{finish.countDown();}
            task.get(10,TimeUnit.SECONDS);assertThat(f.mapper.get(key).state()).isEqualTo("BOUND");assertThat(files.exists(key)).isTrue();
        }
    }
    @Test void removalFailurePersistsApprovalAndResumesWithoutDuplicateAudit()throws Exception {
        try(var f=new Fixture();var files=spy(f.store(directory))) {
            login("PERM_FILE_REVIEW_MANAGE");byte[] bytes=picture();String key=f.intent("png",bytes);files.write(key,new ByteArrayInputStream(bytes),bytes.length,"image/png");f.mapper.failed(key);
            doThrow(new IOException("injected storage failure")).when(files).remove(key);var review=f.review(files);
            var failed=review.cleanup(new FileReviewService.Request(key,1L,"已核对可以清理"));assertThat(failed.state()).isEqualTo("CLEANUP_PENDING");assertThat(failed.errorCode()).isEqualTo("CLEANUP_FAILED");assertThat(files.exists(key)).isTrue();
            doCallRealMethod().when(files).remove(key);review.process(key);assertThat(f.mapper.get(key).state()).isEqualTo("CLEANED");assertThat(review.history(key)).hasSize(1);
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM "+f.prefix+"operation_log",Integer.class)).isEqualTo(1);
        }
    }
    @Test void permissionIsExplicitAndAgentOrPdaCannotUseItEvenWithAdminRole()throws Exception {
        try(var f=new Fixture();var files=f.store(directory)) {
            var review=f.review(files);
            for(String[] roles:List.of(new String[]{"PERM_PI_WRITE","PERM_DATA_EXPORT"},new String[]{"ROLE_WEB_AGENT","ROLE_SUPER_ADMIN","PERM_FILE_REVIEW_MANAGE"},new String[]{"ROLE_PDA_OPERATOR","ROLE_SUPER_ADMIN","PERM_FILE_REVIEW_MANAGE"})) {
                login(roles);assertThatThrownBy(()->review.list(null,1,20)).isInstanceOf(BusinessException.class);
                assertThatThrownBy(()->review.history("key")).isInstanceOf(BusinessException.class);
                assertThatThrownBy(()->review.cleanup(new FileReviewService.Request("key",0L,"reason"))).isInstanceOf(BusinessException.class);
            }
            login("PERM_FILE_REVIEW_MANAGE");assertThat(review.list(null,1,20).total()).isZero();
            assertThatThrownBy(()->review.list("unexpected",1,20)).hasMessageContaining("参数");
        }
    }
    @Test void archiveWorkerBindsBothFormatsAndPreservesFirstWhenSecondWriteFails()throws Exception {
        try(var f=new Fixture();var files=f.store(directory)) {
            var renderer=mock(PiDocumentService.class);byte[] pdf="test-pdf".getBytes(),xlsx="test-xlsx".getBytes();
            when(renderer.pdf(any())).thenReturn(pdf);when(renderer.excel(any())).thenThrow(new IllegalStateException("render fail"));
            f.exports.enqueue(1,"{}","TEST");
            try(var worker=new PiExportWorker(f.exports,renderer,files,new com.fasterxml.jackson.databind.ObjectMapper(),f.manager)){assertThat(worker.processNext()).isTrue();}
            var job=f.exports.get(1);assertThat(job.status()).isEqualTo("FAILED");assertThat(f.mapper.get(job.pdfKey()).state()).isEqualTo("BOUND");
            doReturn(xlsx).when(renderer).excel(any());f.exports.retry(1,job.revision());
            try(var worker=new PiExportWorker(f.exports,renderer,files,new com.fasterxml.jackson.databind.ObjectMapper(),f.manager)){worker.processNext();}
            assertThat(f.exports.get(1).status()).isEqualTo("READY");assertThat(f.exports.get(1).pdfKey()).isEqualTo(job.pdfKey());assertThat(f.mapper.count("BOUND")).isEqualTo(2);
        }
    }
    @Test void partialWriteAndFailedAutomaticRemovalStillLeaveReviewableIntent()throws Exception {
        try(var f=new Fixture();var files=new ProductionFileStore(directory.toString(),"local","","","","") {
            @Override public void write(String key,InputStream input,long size,String type)throws IOException {super.write(key,new ByteArrayInputStream(new byte[]{1}),1,type);throw new IOException("partial write");}
            @Override public void remove(String key)throws IOException {throw new IOException("cleanup unavailable");}
        }) {
            ReflectionTestUtils.setField(files,"uploadJournal",f.journal);login("ROLE_SUPER_ADMIN");
            var service=f.confirmations(files);var upload=new MockMultipartFile("file","proof.png","image/png",picture());
            assertThatThrownBy(()->f.tx.executeWithoutResult(s->{try{service.upload(1,"partial","CUSTOMER",upload);}catch(IOException e){throw new UncheckedIOException(e);}})).hasRootCauseMessage("partial write");
            assertThat(f.confirmations.count(1)).isZero();var rows=f.mapper.list("REVIEW",0,10);assertThat(rows).hasSize(1);assertThat(files.exists(rows.getFirst().objectKey())).isTrue();
            var checked=f.review(files).reconcile(new FileReviewService.Request(rows.getFirst().objectKey(),rows.getFirst().version(),"对象写入及自动清理失败"));assertThat(checked.objectState()).isEqualTo("PRESENT");
        }
    }
    @Test void allMainPoolConnectionsMayUploadWithoutIntentPoolStarvation()throws Exception {
        try(var f=new Fixture();var pool=Executors.newFixedThreadPool(2)) {
            var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(System.getenv("BIKE_MYSQL_TEST_URL"));config.setUsername(System.getenv("BIKE_MYSQL_TEST_USER"));config.setPassword(System.getenv("BIKE_MYSQL_TEST_PASSWORD"));config.setMaximumPoolSize(2);config.setConnectionTimeout(1500);
            try(var main=new com.zaxxer.hikari.HikariDataSource(config)) {
                var tx=new TransactionTemplate(new DataSourceTransactionManager(main));var sql=new JdbcTemplate(main);var ready=new CyclicBarrier(2);
                var intents=new FileUploadIntentStore(f.source,f.prefix+"file_upload");byte[] bytes=picture();String digest=hash(bytes);
                Callable<String> task=()->tx.execute(s->{sql.queryForObject("SELECT 1",Integer.class);try{ready.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
                    String key="local:"+UUID.randomUUID()+".png";intents.begin(key,new FileUploadJournal.Context("SKU_IMAGE","1","parallel.png",digest),bytes.length,"image/png",null,"test");s.setRollbackOnly();return key;});
                var first=pool.submit(task);var second=pool.submit(task);assertThat(f.mapper.get(first.get(10,TimeUnit.SECONDS)).state()).isEqualTo("PENDING");assertThat(f.mapper.get(second.get(10,TimeUnit.SECONDS)).state()).isEqualTo("PENDING");
            }
        }
    }
}
