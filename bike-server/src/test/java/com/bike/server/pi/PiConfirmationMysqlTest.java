package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class PiConfirmationMysqlTest {
    @TempDir Path directory;
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    @Test void attachmentScopeIntegrityAndAuditRollbackUseTheSameBusinessTransaction()throws Exception {
        try(var f=new PiPublishingMysqlTest.Fixture();var store=f.store(directory)) {
            var view=f.pi();var pis=mock(PiService.class);when(pis.get(1)).thenReturn(view);
            var service=new PiConfirmationService(pis,f.pis,f.confirmations,store,new com.bike.server.common.BusinessUploadValidator(),f.logs);
            byte[] bytes=PiImageServiceTest.picture();var upload=new MockMultipartFile("file","confirmation.png","image/png",bytes);
            PiPublishingMysqlTest.login("ROLE_WEB_OPERATOR","PERM_PI_WRITE","PERM_PI_READ");
            var a=f.tx.execute(s->{try{return service.upload(1,"customer","CUSTOMER",upload);}catch(IOException e){throw new UncheckedIOException(e);}});
            var b=f.tx.execute(s->{try{return service.upload(1,"internal","INTERNAL",upload);}catch(IOException e){throw new UncheckedIOException(e);}});
            assertThat(service.download(1,a.id()).bytes()).isEqualTo(bytes);assertThat(service.list(1)).hasSize(2);
            assertThat(f.json.writeValueAsString(a)).doesNotContain("objectKey","requestId");
            PiPublishingMysqlTest.login("ROLE_WEB_AGENT","ROLE_SUPER_ADMIN");assertThat(service.list(1)).extracting(PiConfirmationAttachment::id).containsExactly(a.id());
            assertThatThrownBy(()->service.download(1,b.id())).hasMessageContaining("无权");assertThatThrownBy(()->service.upload(1,"forbidden","CUSTOMER",upload)).hasMessageContaining("代理商");
            PiPublishingMysqlTest.login("ROLE_WEB_OPERATOR","PERM_PI_WRITE","PERM_PI_READ");
            when(f.logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(0);
            assertThatThrownBy(()->f.tx.execute(s->{try{return service.upload(1,"rollback","CUSTOMER",upload);}catch(IOException e){throw new UncheckedIOException(e);}})).hasMessageContaining("审计");
            assertThat(f.confirmations.count(1)).isEqualTo(2);try(var files=Files.list(directory)){assertThat(files.count()).isEqualTo(2);}
            assertThatThrownBy(()->service.upload(1,"html","CUSTOMER",new MockMultipartFile("file","bad.pdf","application/pdf","<html>bad</html>".getBytes()))).isInstanceOf(com.bike.server.common.BusinessException.class);
            Files.write(directory.resolve(a.objectKey().substring(6)),new byte[]{1});assertThatThrownBy(()->service.download(1,a.id())).hasMessageContaining("完整性");
        }
    }
    @Test void repeatedConcurrentUploadCreatesOneFileAndTerminalPiRejectsNewRequests()throws Exception {
        try(var f=new PiPublishingMysqlTest.Fixture();var store=f.store(directory);var pool=Executors.newFixedThreadPool(2)) {
            PiPublishingMysqlTest.login("ROLE_SUPER_ADMIN");f.templateService().publish(PiPublishingMysqlTest.template("1"));
            var view=f.pi();var pis=mock(PiService.class);var initialRead=ThreadLocal.withInitial(()->false);var bothRead=new CyclicBarrier(2);
            when(pis.get(1)).thenAnswer(call->{
                if(initialRead.get()) {
                    initialRead.set(false);
                    new JdbcTemplate(f.source).queryForObject("SELECT COUNT(*) FROM "+f.prefix+"pi_confirmation_attachment",Long.class);
                    bothRead.await(5,TimeUnit.SECONDS);
                }
                return view;
            });
            // Establish a read snapshot BEFORE the parent lock, reproducing PiService.get in the real HTTP path.
            when(f.pis.lockPi(1)).thenAnswer(call->new JdbcTemplate(f.source).queryForObject("SELECT id FROM "+f.prefix+"pi_template WHERE id=1 FOR UPDATE",Long.class));
            var target=new PiConfirmationService(pis,f.pis,f.confirmations,store,new com.bike.server.common.BusinessUploadValidator(),f.logs);
            var proxy=new org.springframework.aop.framework.ProxyFactory(target);proxy.setProxyTargetClass(true);
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(f.manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            var service=(PiConfirmationService)proxy.getProxy();
            var file=new MockMultipartFile("file","confirmation.png","image/png",PiImageServiceTest.picture());var start=new CountDownLatch(1);
            Callable<PiConfirmationAttachment> task=()->{start.await();initialRead.set(true);PiPublishingMysqlTest.login("ROLE_WEB_OPERATOR","PERM_PI_WRITE","PERM_PI_READ");try{return service.upload(1,"same","CUSTOMER",file);}finally{SecurityContextHolder.clearContext();initialRead.remove();}};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertThat(a.get(15,TimeUnit.SECONDS).id()).isEqualTo(b.get(15,TimeUnit.SECONDS).id());
            assertThat(f.confirmations.count(1)).isEqualTo(1);try(var files=Files.list(directory)){assertThat(files.count()).isEqualTo(1);}
            assertThatThrownBy(()->f.tx.execute(s->{try{return service.upload(1,"same","INTERNAL",file);}catch(IOException e){throw new UncheckedIOException(e);}})).hasMessageContaining("其他确认附件");
            view.setStatus("SUPERSEDED");assertThatThrownBy(()->f.tx.execute(s->{try{return service.upload(1,"new","CUSTOMER",file);}catch(IOException e){throw new UncheckedIOException(e);}})).hasMessageContaining("不能追加");
        }
    }
}
