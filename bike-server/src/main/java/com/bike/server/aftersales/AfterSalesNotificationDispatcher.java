package com.bike.server.aftersales;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SMTP runs outside business transactions; every claim/result write is an independent short statement. */
@Component
public class AfterSalesNotificationDispatcher {
    private static final Logger LOG=LoggerFactory.getLogger(AfterSalesNotificationDispatcher.class);
    private final AfterSalesMapper mapper;
    private final AfterSalesMailConfig config;
    private final AfterSalesMailTransport transport;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("after-sales-mail").factory());
    public AfterSalesNotificationDispatcher(AfterSalesMapper mapper,AfterSalesMailConfig config,AfterSalesMailTransport transport){this.mapper=mapper;this.config=config;this.transport=transport;}

    @Scheduled(fixedDelayString="${bike.after-sales.mail.poll-ms:30000}",initialDelayString="${bike.after-sales.mail.poll-ms:30000}")
    public void tick() {
        if(!config.getEnabled()||!running.compareAndSet(false,true))return;
        try {worker.submit(()->{try{dispatch();}catch(RuntimeException e){LOG.error("After-sales email dispatch failed; queued records remain recoverable");}finally{running.set(false);}});}
        catch(java.util.concurrent.RejectedExecutionException stopped){running.set(false);}
    }
    @jakarta.annotation.PreDestroy public void shutdown(){worker.shutdownNow();}

    public void dispatch() {
        if(!config.getEnabled())return;
        mapper.expireExhaustedNotifications();
        for(long id:mapper.selectDueNotifications())deliver(id);
    }
    void deliver(long id) {
        if(!config.getEnabled())return;
        String token=UUID.randomUUID().toString();if(mapper.claimNotification(id,token)!=1)return;
        var n=mapper.selectNotification(id);if(n==null||!token.equals(n.claimToken()))return;
        String recipient=AfterSalesMailTransport.address("STAFF".equals(n.audience())?config.getStaffRecipient():n.recipient());
        String status="SENT",error=null;
        if(recipient==null){status="BLOCKED";error="RECIPIENT_MISSING_OR_INVALID";}
        else if(!transport.configured()){status="BLOCKED";error="SMTP_NOT_CONFIGURED";}
        else {
            try {transport.send(n,recipient);}
            catch(Exception failure){status=n.attemptCount()>=5?"FAILED":"RETRY";error="SMTP_SEND_FAILED";}
        }
        // Do not retry immediately if the result write fails: a successful SMTP send may already have occurred.
        int delay=Math.min(3600,60*(1<<Math.min(6,n.attemptCount()-1)));
        if(mapper.finishNotification(id,token,status,error,delay,recipient==null?n.recipient():recipient)!=1)
            LOG.warn("After-sales notification {} lost its delivery claim; result not overwritten",id);
    }
}
