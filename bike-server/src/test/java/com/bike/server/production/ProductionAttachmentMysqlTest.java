package com.bike.server.production;

import com.bike.server.common.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class ProductionAttachmentMysqlTest {
    @TempDir Path directory;
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    static void login(String... rights){loginAs("images",rights);}
    static void loginAs(String username,String... rights){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username,null,Arrays.stream(rights).map(SimpleGrantedAuthority::new).toList()));}
    @Test void validatedSourceReadOnceStoredDigestAndOwnershipArePreserved()throws Exception {
        try(var f=new Fixture();var store=f.store(directory)) {
            login("PERM_PRODUCTION_MANAGE");var service=f.service(store,f.mapper);byte[] bytes=BusinessUploadValidatorTest.webp();
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var file=new MockMultipartFile("file","商品.webp","image/webp",bytes){@Override public InputStream getInputStream(){assertThat(calls.incrementAndGet()).isEqualTo(1);return new ByteArrayInputStream(bytes);}};
            var row=service.uploadSku(1,"original",file);String id=(String)row.get("id");
            assertThat(service.content(id).getContentAsByteArray()).isEqualTo(bytes);assertThat(row).doesNotContainKeys("objectKey","requestId");
            byte[] corrupt=bytes.clone();corrupt[corrupt.length-1]^=1;Files.write(directory.resolve(id+".webp"),corrupt);
            assertThatThrownBy(()->service.content(id)).hasMessageContaining("完整性");
            assertThat(service.uploadSku(1,"original",new MockMultipartFile("file","商品.webp","image/webp",bytes)).get("id")).isEqualTo(id);
            assertThatThrownBy(()->service.uploadSku(1,"original",new MockMultipartFile("file","other.webp","image/webp",bytes))).hasMessageContaining("其他生产图片");
            assertThatThrownBy(()->service.uploadSku(999,"new",file)).hasMessageContaining("SKU不存在");
            login("PERM_ASSEMBLY_OPERATE");assertThatThrownBy(()->service.uploadSku(1,"bad",file)).isInstanceOf(BusinessException.class);
            assertThat(service.upload("issue-1","original",new MockMultipartFile("file","问题.webp","image/webp",bytes))).containsEntry("contentType","image/webp");
            login("ROLE_WEB_AGENT","ROLE_SUPER_ADMIN");assertThatThrownBy(()->service.content(id)).hasMessageContaining("代理商");
            login("PERM_ASSEMBLY_OPERATE");
            ((UsernamePasswordAuthenticationToken)SecurityContextHolder.getContext().getAuthentication()).setDetails(new com.bike.server.auth.UserAuthRecord(1,"images","","AGENT",1L,null,true));
            assertThatThrownBy(()->service.content(id)).hasMessageContaining("代理商");
            assertThatThrownBy(()->service.upload("issue-1","agent",new MockMultipartFile("file","proof.webp","image/webp",bytes))).hasMessageContaining("代理商");
            assertThat(f.count()).isEqualTo(2);
        }
    }
    @Test void sameRequestConcurrentUploadsCommitOneFileAndOneAudit()throws Exception {
        try(var f=new Fixture();var store=f.store(directory);var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            var mapper=f.wrap((method,args)->{if(method.getName().equals("selectSku")){var result=f.invoke(method,args);barrier.await(5,TimeUnit.SECONDS);return result;}return f.invoke(method,args);});
            var service=f.service(store,mapper);byte[] bytes=BusinessUploadValidatorTest.png();
            Callable<Map<String,Object>> task=()->{login("PERM_PRODUCTION_MANAGE");try{return service.uploadSku(1,"same",new MockMultipartFile("file","same.png","image/png",bytes));}finally{SecurityContextHolder.clearContext();}};
            var first=pool.submit(task);var second=pool.submit(task);assertThat(first.get(15,TimeUnit.SECONDS).get("id")).isEqualTo(second.get(15,TimeUnit.SECONDS).get("id"));
            assertThat(f.count()).isEqualTo(1);assertThat(f.auditCount()).isEqualTo(1);assertFiles(1);
        }
    }
    @Test void failedInsertAuditStorageAndOuterTransactionLeaveNoFilesOrMetadata()throws Exception {
        try(var f=new Fixture();var store=f.store(directory)) {
            login("PERM_PRODUCTION_MANAGE");var file=new MockMultipartFile("file","proof.png","image/png",BusinessUploadValidatorTest.png());
            for(String fail:List.of("insertSkuAttachment","insertAudit")) {
                var mapper=f.wrap((method,args)->method.getName().equals(fail)?0:f.invoke(method,args));var service=f.service(store,mapper);
                assertThatThrownBy(()->service.uploadSku(1,fail,file)).hasMessageContaining("保存失败");assertThat(f.count()).isZero();assertThat(f.auditCount()).isZero();assertFiles(0);
            }
            var corrupt=new ProductionFileStore(directory.toString(),"local","","","","") {
                @Override public void write(String key,InputStream stream,long size,String type)throws IOException {super.write(key,new ByteArrayInputStream(new byte[]{1}),1,type);}
            };
            assertThatThrownBy(()->f.service(corrupt,f.mapper).uploadSku(1,"corrupt",file)).hasMessageContaining("完整性");assertFiles(0);assertThat(f.count()).isZero();
            var tx=new TransactionTemplate(f.manager);tx.executeWithoutResult(status->{try{f.service(store,f.mapper).uploadSku(1,"outer",file);}catch(IOException e){throw new UncheckedIOException(e);}status.setRollbackOnly();});
            assertThat(f.count()).isZero();assertThat(f.auditCount()).isZero();assertFiles(0);
        }
    }
    @Test void batchCommitsSuccessAndFailedItemRetriesWithoutDuplicatingSuccessfulItems()throws Exception {
        try(var f=new Fixture();var store=f.store(directory)) {
            login("PERM_PRODUCTION_MANAGE");var service=f.service(store,f.mapper);
            var good=new MockMultipartFile("files","proof.webp","image/webp",BusinessUploadValidatorTest.webp());
            var bad=new MockMultipartFile("files","broken.webp","image/webp",new byte[]{1});
            var request=new org.springframework.mock.web.MockMultipartHttpServletRequest();request.addFile(good);request.addFile(bad);
            var result=AttachmentBatch.upload(List.of("good","retry"),List.of(good,bad),request,(id,file)->service.uploadSku(1,id,file));
            assertThat(result).extracting(AttachmentBatch.Item::success).containsExactly(true,false);assertThat(f.count()).isEqualTo(1);
            var repaired=new MockMultipartFile("files","repaired.webp","image/webp",BusinessUploadValidatorTest.webp());
            var retry=new org.springframework.mock.web.MockMultipartHttpServletRequest();retry.addFile(good);retry.addFile(repaired);
            var saved=AttachmentBatch.upload(List.of("good","retry"),List.of(good,repaired),retry,(id,file)->service.uploadSku(1,id,file));
            assertThat(saved).extracting(AttachmentBatch.Item::success).containsExactly(true,true);assertThat(f.count()).isEqualTo(2);assertThat(f.auditCount()).isEqualTo(2);assertFiles(2);
        }
    }
    @Test void skuDraftUploadIsIdempotentOwnerScopedAndBindable()throws Exception {
        try(var f=new Fixture();var store=f.store(directory)) {
            login("PERM_PRODUCTION_MANAGE");var service=f.service(store,f.mapper);byte[] bytes=BusinessUploadValidatorTest.webp();
            var first=service.uploadSkuDraft("draft-session","image-one",new MockMultipartFile("file","商品.webp","image/webp",bytes));
            assertThat(service.uploadSkuDraft("draft-session","image-one",new MockMultipartFile("file","商品.webp","image/webp",bytes)).get("id"))
                .isEqualTo(first.get("id"));
            assertThat(f.countDraft("draft-session")).isEqualTo(1);
            loginAs("other-user","PERM_PRODUCTION_MANAGE");
            assertThatThrownBy(()->service.uploadSkuDraft("draft-session","image-two",new MockMultipartFile("file","其他.webp","image/webp",bytes)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("其他账号");
            login("PERM_PRODUCTION_MANAGE");
            assertThat(f.mapper.bindDraftAttachments("draft-session",1)).isEqualTo(1);
            assertThat(f.mapper.deleteSkuImageDraft("draft-session")).isEqualTo(1);
            assertThat(f.mapper.selectSkuAttachments(1)).extracting(row->row.get("id")).containsExactly(first.get("id"));
        }
    }
    private void assertFiles(long n)throws IOException{try(var stream=Files.list(directory)){assertThat(stream.count()).isEqualTo(n);}}
    @FunctionalInterface interface Invocation{Object invoke(Method method,Object[] args)throws Throwable;}
    static final class Fixture implements AutoCloseable {
        final String prefix="pat_"+UUID.randomUUID().toString().replace("-","").substring(0,12)+"_";
        final org.apache.ibatis.datasource.unpooled.UnpooledDataSource source=ProductionMysqlTest.dataSource();
        final DataSourceTransactionManager manager=new DataSourceTransactionManager(source);
        final JdbcTemplate jdbc=new JdbcTemplate(source);final List<String> created=new ArrayList<>();final ProductionMapper mapper;
        Fixture()throws Exception {
            try {
                create("product_model","id BIGINT PRIMARY KEY,model_name VARCHAR(40)");
                create("vehicle","id BIGINT PRIMARY KEY,frame_sn VARCHAR(128),vehicle_qr_code VARCHAR(200)");
                create("production_sku","id BIGINT PRIMARY KEY,model_id BIGINT,sku_code VARCHAR(40),product_name VARCHAR(40),specification VARCHAR(40),ean VARCHAR(40),upc VARCHAR(40),template_id BIGINT,enabled BOOLEAN");
                create("operation_log","id BIGINT PRIMARY KEY AUTO_INCREMENT,business_type VARCHAR(40),business_id VARCHAR(64),action VARCHAR(100),operator_name VARCHAR(64),remark VARCHAR(300)");
                try(var input=Resources.getResourceAsStream("mapper/ProductionSchemaMapper.xml")) {
                    String xml=new String(input.readAllBytes(),StandardCharsets.UTF_8);
                    for(String name:List.of("Issue","Attachment")) {
                        var match=java.util.regex.Pattern.compile("<update id=\"create"+name+"\">(.*?)</update>",java.util.regex.Pattern.DOTALL).matcher(xml);if(!match.find())throw new IllegalStateException("Missing fixture DDL");
                        jdbc.execute(match.group(1).replace("bike_",prefix));created.add(prefix+"production_"+name.toLowerCase(Locale.ROOT));
                    }
                }
                try(var input=Resources.getResourceAsStream("db/migration/V47__production_attachment_integrity.sql")) {
                    String ddl=new String(input.readAllBytes(),StandardCharsets.UTF_8);
                    jdbc.execute(ddl.substring(ddl.indexOf("ALTER TABLE bike_production_attachment"),ddl.indexOf("ALTER TABLE bike_pi_payment_attachment")).trim().replace("bike_",prefix));
                }
                try(var input=Resources.getResourceAsStream("db/migration/V56__sku_image_upload_drafts.sql")) {
                    String ddl=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix);
                    for(String statement:ddl.split(";"))if(!statement.isBlank())jdbc.execute(statement.trim());
                    created.add(prefix+"sku_image_draft");
                }
                jdbc.update("INSERT INTO "+prefix+"product_model VALUES(1,'model')");jdbc.update("INSERT INTO "+prefix+"production_sku(id,model_id,sku_code,product_name,enabled) VALUES(1,1,'sku','测试图片',1)");jdbc.update("INSERT INTO "+prefix+"production_issue(id,code,reason,created_by) VALUES('issue-1','frame','problem','test')");
                for(String statement:List.of("addModelCode","addSkuFields","createSkuNames","addIssueUpdatedAt")) {
                    jdbc.execute(ProductionFixtureSql.statement(statement,prefix));
                    if(statement.equals("createSkuNames"))created.add(prefix+"production_sku_name");
                }
                var config=new Configuration(new Environment("production-images",new SpringManagedTransactionFactory(),source));
                for(String name:List.of("InventoryPolicyMapper","ProductionMapper"))try(var input=Resources.getResourceAsStream("mapper/"+name+".xml")) {String xml=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("bike_",prefix);new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),config,name,config.getSqlFragments()).parse();}
                mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(ProductionMapper.class);
            }catch(Exception e){close();throw e;}
        }
        void create(String name,String ddl){jdbc.execute("CREATE TABLE "+prefix+name+"("+ddl+") ENGINE=InnoDB");created.add(prefix+name);}
        ProductionFileStore store(Path path){return new ProductionFileStore(path.toString(),"local","","","","");}
        ProductionAttachmentService service(ProductionFileStore files,ProductionMapper mapper){
            var proxy=new org.springframework.aop.framework.ProxyFactory(new ProductionAttachmentService(mapper,files,new BusinessUploadValidator(),null));proxy.setProxyTargetClass(true);
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));return (ProductionAttachmentService)proxy.getProxy();
        }
        Object invoke(Method method,Object[] args)throws Throwable{try{return method.invoke(mapper,args);}catch(InvocationTargetException e){throw e.getCause();}}
        ProductionMapper wrap(Invocation call){return (ProductionMapper)Proxy.newProxyInstance(ProductionMapper.class.getClassLoader(),new Class<?>[]{ProductionMapper.class},(proxy,method,args)->call.invoke(method,args));}
        long count(){return jdbc.queryForObject("SELECT COUNT(*) FROM "+prefix+"production_attachment",Long.class);}
        long countDraft(String draftToken){return jdbc.queryForObject("SELECT COUNT(*) FROM "+prefix+"production_attachment WHERE draft_token=?",Long.class,draftToken);}
        long auditCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM "+prefix+"operation_log",Long.class);}
        @Override public void close(){for(String table:created.reversed())jdbc.execute("DROP TABLE "+table);}
    }
}
