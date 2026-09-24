package com.bike.server.system;

import com.bike.server.common.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class CustomerScopeService {
    private final CustomerScopeMapper mapper;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    public CustomerScopeService(CustomerScopeMapper mapper,OperationLogMapper logs,ObjectMapper json) {
        this.mapper=mapper;this.logs=logs;this.json=json;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View get(long userId) {
        manage();var user=require(mapper.user(userId));
        return view(user.id(),user.revision());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<CustomerScopeMapper.UserOption> users(String keyword,long page,long pageSize) {
        manage();var search=search(keyword);long p=page(page),size=size(pageSize);
        return new PageResponse<>(mapper.users(search,(p-1)*size,size),p,size,mapper.userCount(search));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<CustomerScopeMapper.CustomerOption> customers(String keyword,long page,long pageSize) {
        manage();var search=search(keyword);long p=page(page),size=size(pageSize);
        return new PageResponse<>(mapper.customerOptions(search,(p-1)*size,size),p,size,mapper.customerCount(search));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public View save(long userId,Request request) {
        manage();
        if(request==null||request.revision()<0||request.customerIds()==null||request.customerIds().size()>5000
                ||request.customerIds().stream().anyMatch(id->id==null||id<=0)
                ||new HashSet<>(request.customerIds()).size()!=request.customerIds().size()
                ||request.reason()==null||request.reason().isBlank()||request.reason().length()>500)
            throw new BusinessException("INVALID_PARAMETER","客户范围、版本及修改原因不合法");
        var user=require(mapper.lockUser(userId));
        if(user.revision()!=request.revision())throw new BusinessException("CUSTOMER_SCOPE_CONFLICT","客户授权已变更，请刷新后重试");
        for(long id:request.customerIds())if(mapper.customer(id)==null)throw new BusinessException("CUSTOMER_NOT_FOUND","客户不存在");
        var before=view(userId,user.revision());
        mapper.clear(userId);
        for(long id:request.customerIds().stream().sorted().toList())
            if(mapper.grant(userId,id,CurrentOperator.id())!=1)throw failed();
        if(mapper.advance(userId,user.revision())!=1)throw failed();
        var after=view(userId,user.revision()+1);
        try {
            if(logs.insert("CUSTOMER_SCOPE",Long.toString(userId),"调整客户授权范围",CurrentOperator.id(),CurrentOperator.username(),
                    json.writeValueAsString(before),json.writeValueAsString(after),CurrentOperator.clientIp(),request.reason().trim())!=1)throw failed();
        }catch(JsonProcessingException error){throw new IllegalStateException(error);}
        return after;
    }
    private static void manage(){CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("CUSTOMER_SCOPE_MANAGE");}
    private View view(long userId,long revision){return new View(userId,revision,mapper.customers(userId),mapper.assignedCustomers(userId));}
    private static String search(String value){if(value==null||value.isBlank())return null;if(value.length()>200)throw new BusinessException("INVALID_PARAMETER","搜索内容不能超过200字");return value.trim();}
    private static long page(long value){return Math.min(10_000_000,Math.max(1,value));}
    private static long size(long value){return Math.min(100,Math.max(1,value));}
    private static CustomerScopeMapper.User require(CustomerScopeMapper.User user){
        if(user==null||!Set.of("WEB_ADMIN","WEB_OPERATOR").contains(user.userType()))
            throw new BusinessException("CUSTOMER_SCOPE_USER_INVALID","只能为内部 Web 账号配置客户范围");
        return user;
    }
    private static BusinessException failed(){return new BusinessException("CUSTOMER_SCOPE_SAVE_FAILED","客户授权或审计保存失败");}
    public record Request(@Min(0) long revision,@NotNull @Size(max=5000) List<@NotNull @Positive Long> customerIds,@NotBlank @Size(max=500) String reason){}
    public record View(long userId,long revision,List<Long> customerIds,List<CustomerScopeMapper.CustomerOption> customers){}
}
