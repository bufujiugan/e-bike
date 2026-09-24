package com.bike.server.master;

import com.bike.server.common.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class CustomerProfileService {
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.common.CustomerScopeAccess customerScope;
    private final CustomerProfileMapper mapper;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    private final Validator validator;
    public CustomerProfileService(CustomerProfileMapper mapper,OperationLogMapper logs,ObjectMapper json,Validator validator) {
        this.mapper=mapper;this.logs=logs;this.json=json;this.validator=validator;
    }
    public CustomerProfile get(long id) {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requireAnyPermission("MASTER_READ","PI_WRITE","CUSTOMER_PROFILE_WRITE");
        customerScope.requireCustomer(id);
        return require(id);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public CustomerProfile update(long id,CustomerProfileRequest request) {
        CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("CUSTOMER_PROFILE_WRITE");
        customerScope.requireCustomer(id);
        if(request==null||!validator.validate(request).isEmpty())throw invalid();
        String currency=clean(request.preferredCurrency());
        if(currency!=null) {
            currency=currency.toUpperCase(Locale.ROOT);
            try { Currency.getInstance(currency); } catch(IllegalArgumentException error) { throw invalid(); }
            if(!currency.matches("[A-Z]{3}"))throw invalid();
        }
        var r=new CustomerProfileRequest(request.revision(),clean(request.address()),clean(request.country()),clean(request.email()),currency,clean(request.businessOwner()),request.reason().trim());
        if(mapper.lockCustomer(id)==null)throw missing();
        var before=require(id);
        if(before.revision()!=r.revision())throw new BusinessException("CUSTOMER_PROFILE_CONFLICT","客户商务资料已更新，请刷新后重新核对");
        int changed=before.revision()==0?mapper.insert(id,r,CurrentOperator.id()):mapper.update(id,r,CurrentOperator.id());
        if(changed!=1)throw new BusinessException("CUSTOMER_PROFILE_SAVE_FAILED","客户商务资料保存失败");
        var after=require(id);
        try {
            if(logs.insert("CUSTOMER_PROFILE",Long.toString(id),"更新客户商务资料",CurrentOperator.id(),CurrentOperator.username(),
                    json.writeValueAsString(before),json.writeValueAsString(after),CurrentOperator.clientIp(),r.reason())!=1)
                throw new BusinessException("CUSTOMER_PROFILE_SAVE_FAILED","客户商务资料审计失败");
        } catch(JsonProcessingException error) { throw new IllegalStateException(error); }
        return after;
    }
    private CustomerProfile require(long id){var p=mapper.get(id);if(p==null)throw missing();return p;}
    private static BusinessException missing(){return new BusinessException("CUSTOMER_NOT_FOUND","客户不存在");}
    private static BusinessException invalid(){return new BusinessException("CUSTOMER_PROFILE_INVALID","客户商务资料格式不正确，请检查邮箱、币种及字段长度");}
    private static String clean(String text){return text==null||text.isBlank()?null:text.trim();}
}
