package com.bike.server.aftersales;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static com.bike.server.aftersales.AfterSalesServiceTest.*;
import static com.bike.server.aftersales.AfterSalesPlanMysqlTest.*;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class AfterSalesNotificationMysqlTest {
    static AfterSalesMailConfig config() {
        var c=new AfterSalesMailConfig();c.setEnabled(true);c.setHost("127.0.0.1");c.setPort(2525);c.setFrom("service@example.test");c.setStaffRecipient("staff@example.test");c.setStartTls(false);return c;
    }
    static AfterSalesNotification first(AfterSalesMysqlTest.Fixture f,long id){return f.mapper.selectNotifications(id,0,100).getLast();}
    static AfterSalesMailTransport fake(AfterSalesMailConfig c,AtomicInteger calls,boolean fail) {
        return new AfterSalesMailTransport(c){@Override public void send(AfterSalesNotification n,String recipient)throws Exception {calls.incrementAndGet();if(fail)throw new IOException("synthetic SMTP failure, must never persist raw details");}};
    }

    @Test void businessEventsAreAtomicUniqueAndDoNotExposePrivateContent() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);assertThat(f.mapper.countNotifications(id)).isEqualTo(1);
            var submitted=first(f,id);assertThat(submitted.audience()).isEqualTo("STAFF");
            f.admin(()->f.service.addMessage(id,new AfterSalesMessageRequest("secret liability and cost","INTERNAL")));
            f.admin(()->f.service.saveSolution(id,plan(null,"Motor",1,false)));
            assertThat(f.mapper.countNotifications(id)).isEqualTo(1);
            f.agent(()->f.service.addMessage(id,new AfterSalesMessageRequest("customer report","CUSTOMER")));
            var latest=f.mapper.selectNotifications(id,0,20).getFirst();assertThat(latest.audience()).isEqualTo("STAFF");
            assertThat(latest.body()).doesNotContain("secret","confidential","customer report");
            f.admin(()->f.mapper.insertNotification(id,submitted.eventId(),submitted.eventType(),submitted.audience(),null,UUID.randomUUID().toString(),"duplicate","duplicate"));
            assertThat(f.mapper.countNotifications(id)).isEqualTo(2);
            var broken=new AfterSalesServiceImpl((AfterSalesMapper)java.lang.reflect.Proxy.newProxyInstance(AfterSalesMapper.class.getClassLoader(),new Class<?>[]{AfterSalesMapper.class},(self,method,args)->{
                if(method.getName().equals("insertNotification"))throw new com.bike.server.common.BusinessException("TEST_OUTBOX_FAILED","queue unavailable");
                try{return method.invoke(f.mapper,args);}catch(InvocationTargetException e){throw e.getCause();}
            }));
            long messages=f.count("after_sales_message"),history=f.count("operation_log");
            assertCode("TEST_OUTBOX_FAILED",()->f.agent(()->broken.addMessage(id,new AfterSalesMessageRequest("rollback","CUSTOMER"))));
            assertThat(f.count("after_sales_message")).isEqualTo(messages);assertThat(f.count("operation_log")).isEqualTo(history);assertThat(f.mapper.countNotifications(id)).isEqualTo(2);
        }
    }

    @Test void disabledDeliveryMissingAddressesAndSmtpConfigurationAreVisibleWithoutSending() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);var c=config();var calls=new AtomicInteger();var transport=fake(c,calls,false);var job=new AfterSalesNotificationDispatcher(f.mapper,c,transport);
            c.setEnabled(false);job.dispatch();assertThat(first(f,id).status()).isEqualTo("PENDING");assertThat(calls).hasValue(0);
            c.setEnabled(true);c.setStaffRecipient("");job.dispatch();assertThat(first(f,id).status()).isEqualTo("BLOCKED");assertThat(first(f,id).lastError()).isEqualTo("RECIPIENT_MISSING_OR_INVALID");
            c.setStaffRecipient("staff@example.test");c.setHost("");
            var service=new AfterSalesNotificationService(f.mapper,c,transport);var blocked=first(f,id);
            f.admin(()->service.retry(id,blocked.id(),new AfterSalesNotificationService.Retry(blocked.revision(),null,"configured recipient")));
            job.dispatch();assertThat(first(f,id).lastError()).isEqualTo("SMTP_NOT_CONFIGURED");assertThat(calls).hasValue(0);
        }
    }

    @Test void dispatchTemplateIncludesPublicTrackingAndRetryAuditFailureRestoresQueue() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);var plan=approve(f,id,plan(null,"Motor",1,false));
            f.admin(()->f.service.addShipment(id,new AfterSalesShipmentRequest("Motor",1,"DHL","PUBLIC-TRACK",false,plan.parts().getFirst().id(),shipping())));
            var n=f.mapper.selectNotifications(id,0,20).getFirst();assertThat(n.eventType()).isEqualTo("SHIPMENT_DISPATCHED");
            assertThat(n.body()).contains("DHL","PUBLIC-TRACK").doesNotContain("confidential","Isolated test address");
            var c=config();var transport=fake(c,new AtomicInteger(),true);new AfterSalesNotificationDispatcher(f.mapper,c,transport).deliver(n.id());
            var failed=f.mapper.selectNotification(n.id());assertThat(failed.status()).isEqualTo("BLOCKED");
            var proxy=(AfterSalesMapper)java.lang.reflect.Proxy.newProxyInstance(AfterSalesMapper.class.getClassLoader(),new Class<?>[]{AfterSalesMapper.class},(self,method,args)->{
                if(method.getName().equals("insertAudit"))return 0;try{return method.invoke(f.mapper,args);}catch(InvocationTargetException e){throw e.getCause();}
            });
            var broken=new AfterSalesNotificationService(proxy,c,transport);
            assertCode("AFTER_SALES_AUDIT_FAILED",()->f.admin(()->broken.retry(id,n.id(),new AfterSalesNotificationService.Retry(failed.revision(),"fixed@example.test","correct recipient"))));
            var after=f.mapper.selectNotification(n.id());assertThat(after.status()).isEqualTo("BLOCKED");assertThat(after.recipient()).isNull();assertThat(after.revision()).isEqualTo(failed.revision());
        }
    }

    @Test void smtpFailuresBackOffAndStopAfterFiveAttemptsAndClaimsRecoverAfterCrash() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);var c=config();var calls=new AtomicInteger();var job=new AfterSalesNotificationDispatcher(f.mapper,c,fake(c,calls,true));var n=first(f,id);
            for(int i=1;i<=5;i++) {
                job.dispatch();var updated=f.mapper.selectNotification(n.id());assertThat(updated.attemptCount()).isEqualTo(i);
                assertThat(updated.lastError()).isEqualTo("SMTP_SEND_FAILED");assertThat(updated.status()).isEqualTo(i==5?"FAILED":"RETRY");
                job.dispatch();assertThat(calls).hasValue(i);
                if(i<5)f.sql("UPDATE "+f.prefix+"after_sales_notification SET next_attempt_at=DATE_SUB(NOW(3),INTERVAL 1 MINUTE) WHERE id="+n.id());
            }
            f.sql("UPDATE "+f.prefix+"after_sales_notification SET status='SENDING',claimed_until=DATE_SUB(NOW(3),INTERVAL 1 MINUTE),attempt_count=5 WHERE id="+n.id());
            job.dispatch();assertThat(f.mapper.selectNotification(n.id()).lastError()).isEqualTo("DELIVERY_RESULT_UNKNOWN");
            f.sql("UPDATE "+f.prefix+"after_sales_notification SET status='SENDING',claimed_until=DATE_SUB(NOW(3),INTERVAL 1 MINUTE),attempt_count=1 WHERE id="+n.id());
            var success=new AfterSalesNotificationDispatcher(f.mapper,c,fake(c,calls,false));success.dispatch();assertThat(f.mapper.selectNotification(n.id()).status()).isEqualTo("SENT");
            assertThat(f.mapper.selectNotification(n.id()).messageKey()).isEqualTo(n.messageKey());
        }
    }

    @Test void concurrentDispatchersCannotSendTheSameClaimTwice() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture();var pool=Executors.newFixedThreadPool(2)) {
            long id=reviewCase(f);var c=config();var calls=new AtomicInteger();var job=new AfterSalesNotificationDispatcher(f.mapper,c,fake(c,calls,false));var start=new CountDownLatch(1);
            Callable<Void> send=()->{start.await();job.dispatch();return null;};var a=pool.submit(send);var b=pool.submit(send);start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertThat(calls).hasValue(1);assertThat(first(f,id).status()).isEqualTo("SENT");assertThat(first(f,id).sentAt()).isNotNull();
        }
    }

    @Test void scheduledTickDoesNotBlockTheSharedSchedulerOrStartOverlappingMailBatches() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);var c=config();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
            var transport=new AfterSalesMailTransport(c){@Override public void send(AfterSalesNotification n,String recipient)throws Exception{calls.incrementAndGet();entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test timeout");}};
            var job=new AfterSalesNotificationDispatcher(f.mapper,c,transport);
            try {
                org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(1),job::tick);
                assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();job.tick();assertThat(calls).hasValue(1);release.countDown();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!"SENT".equals(first(f,id).status())&&System.nanoTime()<deadline)Thread.sleep(10);
                assertThat(first(f,id).status()).isEqualTo("SENT");assertThat(calls).hasValue(1);
            }finally{release.countDown();job.shutdown();}
        }
    }

    @Test void retryRequiresReviewScopeAndRevisionAndPreservesOriginalRecipient() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture()) {
            long id=reviewCase(f);f.sql("UPDATE "+f.prefix+"after_sales_case_contact SET contact_email='original@example.test' WHERE case_id="+id);
            f.admin(()->f.service.review(id,new AfterSalesReviewRequest("REQUEST_INFO","please upload photo")));
            var c=config();var calls=new AtomicInteger();var transport=fake(c,calls,true);var service=new AfterSalesNotificationService(f.mapper,c,transport);
            var job=new AfterSalesNotificationDispatcher(f.mapper,c,transport);job.dispatch();var n=f.mapper.selectNotifications(id,0,20).getFirst();
            assertThat(n.recipient()).isEqualTo("original@example.test");
            assertCode("FORBIDDEN",()->f.agent(()->service.list(id,1,20)));
            assertCode("PERMISSION_DENIED",()->AfterSalesWarehouseMysqlTest.warehouse(f,()->service.list(id,1,20)));
            assertCode("FORBIDDEN",()->f.as("WEB_OPERATOR",null,()->service.list(id,1,20),"PERM_AFTER_SALES_READ","PERM_AFTER_SALES_REVIEW"));
            assertCode("INVALID_PARAMETER",()->f.admin(()->service.retry(id,n.id(),new AfterSalesNotificationService.Retry(n.revision(),"victim@example.test\r\nBcc: other@example.test","invalid"))));
            var retry=f.admin(()->service.retry(id,n.id(),new AfterSalesNotificationService.Retry(n.revision(),"correct@example.test","customer confirmed corrected email")));
            assertThat(retry.recipient()).isEqualTo("correct@example.test");assertThat(retry.originalRecipient()).isEqualTo("original@example.test");
            assertCode("AFTER_SALES_NOTIFICATION_CONFLICT",()->f.admin(()->service.retry(id,n.id(),new AfterSalesNotificationService.Retry(n.revision(),null,"stale"))));
            assertThat(f.admin(()->service.list(id,1,1)).notifications().records()).hasSize(1);
            assertThat(f.admin(()->service.list(id,1,1)).notifications().total()).isEqualTo(2);
            new AfterSalesNotificationDispatcher(f.mapper,c,fake(c,calls,false)).dispatch();var sent=f.mapper.selectNotification(n.id());
            assertCode("AFTER_SALES_NOTIFICATION_CONFLICT",()->f.admin(()->service.retry(id,n.id(),new AfterSalesNotificationService.Retry(sent.revision(),null,"do not repeat success"))));
        }
    }

    @Test void actualLoopbackSmtpCapturesUnicodeTemplateAndStableMessageId() throws Exception {
        try(var f=new AfterSalesMysqlTest.Fixture();var server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));var pool=Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(10000);
            var captured=pool.submit(()->capture(server));
            long id=reviewCase(f);var n=first(f,id);var c=config();c.setPort(server.getLocalPort());
            var job=new AfterSalesNotificationDispatcher(f.mapper,c,new AfterSalesMailTransport(c));job.deliver(n.id());
            var raw=captured.get(15,TimeUnit.SECONDS);var message=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));
            assertThat(message.getSubject()).contains("新案例或补充资料已提交");assertThat(message.getContent().toString()).contains("请登录售后系统");
            assertThat(message.getMessageID()).isEqualTo("<after-sales-"+n.messageKey()+"@ebike.local>");
            assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly("staff@example.test");
            assertThat(f.mapper.selectNotification(n.id()).status()).isEqualTo("SENT");
        }
    }

    private static String capture(ServerSocket server)throws Exception {
        try(var socket=server.accept()) {
            socket.setSoTimeout(10000);var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            var out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII));
            out.write("220 localhost test SMTP\r\n");out.flush();StringBuilder data=new StringBuilder();boolean collecting=false;
            for(String line;(line=in.readLine())!=null;) {
                if(collecting) {
                    if(line.equals(".")){collecting=false;out.write("250 accepted\r\n");out.flush();}
                    else data.append(line.startsWith("..")?line.substring(1):line).append("\r\n");
                }else if(line.startsWith("EHLO")||line.startsWith("HELO")){out.write("250 localhost\r\n");out.flush();}
                else if(line.equals("DATA")){collecting=true;out.write("354 send data\r\n");out.flush();}
                else if(line.equals("QUIT")){out.write("221 bye\r\n");out.flush();break;}
                else {out.write("250 OK\r\n");out.flush();}
            }
            return data.toString();
        }
    }
}
