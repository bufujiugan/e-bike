package com.bike.server.aftersales;

import static com.bike.server.aftersales.AfterSalesAccess.*;
import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AfterSalesNotificationService {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private final AfterSalesMapper mapper;private final AfterSalesMailConfig config;private final AfterSalesMailTransport transport;
    public AfterSalesNotificationService(AfterSalesMapper mapper,AfterSalesMailConfig config,AfterSalesMailTransport transport){this.mapper=mapper;this.config=config;this.transport=transport;}
    public record View(long id,String eventType,String audience,String originalRecipient,String recipient,String status,int attemptCount,int revision,String lastError,LocalDateTime nextAttemptAt,LocalDateTime sentAt,LocalDateTime createdAt) {
        static View of(AfterSalesNotification n){return new View(n.id(),n.eventType(),n.audience(),n.originalRecipient(),n.recipient(),n.status(),n.attemptCount(),n.revision(),n.lastError(),n.nextAttemptAt(),n.sentAt(),n.createdAt());}
    }
    public record Page(PageResponse<View> notifications,boolean sendingEnabled,boolean smtpConfigured) {}
    public record Retry(int revision,String recipient,String reason) {}

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page list(long caseId,long page,long pageSize) {
        requireRead();requireInternal("AFTER_SALES_REVIEW");requireCaseScope(mapper,mapper.selectById(caseId));
        long p=Math.max(1,Math.min(page,Integer.MAX_VALUE)),size=Math.min(100,Math.max(1,pageSize));
        return new Page(new PageResponse<>(mapper.selectNotifications(caseId,(p-1)*size,size).stream().map(View::of).toList(),p,size,mapper.countNotifications(caseId)),config.getEnabled(),transport.configured());
    }

    @Transactional public View retry(long caseId,long id,Retry request) {
        requireRead();requireInternal("AFTER_SALES_REVIEW");requireCaseScope(mapper,mapper.selectForUpdate(caseId));
        var n=mapper.selectNotification(id);if(n==null||n.caseId()!=caseId)throw new BusinessException("AFTER_SALES_NOTIFICATION_NOT_FOUND","通知不存在");
        String reason=AfterSalesShippingDetails.text(request.reason(),1000,true);
        String recipient=n.recipient();
        if(request.recipient()!=null&&!request.recipient().isBlank()) {
            if(!"CUSTOMER".equals(n.audience()))throw new BusinessException("INVALID_PARAMETER","内部通知收件地址请在服务器配置中维护");
            recipient=AfterSalesMailTransport.address(request.recipient());if(recipient==null)throw new BusinessException("INVALID_PARAMETER","收件Email不合法");
        }
        if(mapper.retryNotification(id,request.revision(),recipient)!=1)throw new BusinessException("AFTER_SALES_NOTIFICATION_CONFLICT","通知状态已更新，只有失败、待重试或缺资料的通知可重新排队");
        var after=mapper.selectNotification(id);
        try {
            if(mapper.insertAudit(caseId,"NOTIFICATION_RETRY",CurrentOperator.id(),CurrentOperator.username(),JSON.writeValueAsString(View.of(n)),JSON.writeValueAsString(new RetryAudit(reason,View.of(after))),CurrentOperator.clientIp())!=1)
                throw new BusinessException("AFTER_SALES_AUDIT_FAILED","通知重试审计失败");
        }catch(JsonProcessingException e){throw new BusinessException("AFTER_SALES_AUDIT_FAILED","通知重试审计序列化失败");}
        return View.of(after);
    }
    private record RetryAudit(String reason,View notification) {}
}
